package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds the importer plugins of the {@code ricImporterPlugins} directories (no Spring).
 *
 * <p>In a directory, every {@code *.jar} is one plugin, and every subdirectory is one plugin made
 * of all the jars it contains (for plugins bringing their own libraries). Each plugin gets its own
 * {@link URLClassLoader} whose parent is the backend's class loader: RDF4J, SLF4J and the plugin
 * API come from the backend, so {@link RdfConverterProvider} is the same class on both sides.
 *
 * <p>A plugin that cannot be loaded is reported as a {@link PluginProblem}; it never stops the
 * scan. Names are compared ignoring case: two plugins of the same name in one directory are both
 * refused; between directories, the later one (the user's) replaces the earlier (the bundled).
 */
public final class ImporterPluginScanner {

    /** A directory to scan and the origin of the plugins found in it. */
    public record PluginDirectory(Path path, ImporterOrigin origin) {
    }

    /** The plugins found, the problems met, and the class loaders to close when discarded. */
    public record ScanResult(List<PluginImporter> plugins, List<PluginProblem> problems,
                             List<URLClassLoader> loaders) {
    }

    /** Upper bound on the providers read from one plugin (guards a misbehaving service file). */
    private static final int MAX_PROVIDERS_PER_PLUGIN = 50;

    private final ClassLoader parent;

    public ImporterPluginScanner(ClassLoader parent) {
        this.parent = parent;
    }

    /** Scans the directories in order; later directories take precedence over earlier ones. */
    public ScanResult scan(List<PluginDirectory> directories) {
        Map<String, PluginImporter> byName = new LinkedHashMap<>();
        List<PluginProblem> problems = new ArrayList<>();
        List<URLClassLoader> loaders = new ArrayList<>();

        for (PluginDirectory directory : directories) {
            if (directory.path() == null || !Files.isDirectory(directory.path())) continue;

            // Plugins of this directory, by lower-case name; null marks a duplicated name.
            Map<String, List<PluginImporter>> found = new LinkedHashMap<>();
            for (Path unit : units(directory.path(), problems)) {
                for (PluginImporter plugin : load(unit, directory.origin(), problems, loaders)) {
                    found.computeIfAbsent(key(plugin.id()), k -> new ArrayList<>()).add(plugin);
                }
            }
            for (List<PluginImporter> same : found.values()) {
                if (same.size() > 1) {
                    for (PluginImporter p : same) {
                        problems.add(new PluginProblem(p.location().toString(), "Several plugins of "
                                + directory.path() + " are named \"" + p.id() + "\"; none of them is used.", true));
                    }
                    continue;
                }
                PluginImporter plugin = same.get(0);
                PluginImporter replaced = byName.put(key(plugin.id()), plugin);
                if (replaced != null) {
                    problems.add(new PluginProblem(replaced.location().toString(), "The \"" + plugin.id()
                            + "\" plugin of " + plugin.location() + " is used instead of this one.", false));
                }
            }
        }
        return new ScanResult(List.copyOf(byName.values()), List.copyOf(problems), List.copyOf(loaders));
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The plugins of a directory: its jars, and its subdirectories. Hidden entries are skipped. */
    private static List<Path> units(Path directory, List<PluginProblem> problems) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> Files.isDirectory(p) || isJar(p))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            problems.add(new PluginProblem(directory.toString(), "Unreadable directory: " + e.getMessage(), true));
            return List.of();
        }
    }

    private static boolean isJar(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private List<PluginImporter> load(Path unit, ImporterOrigin origin, List<PluginProblem> problems,
                                      List<URLClassLoader> loaders) {
        List<Path> jars;
        try {
            jars = Files.isDirectory(unit) ? units(unit, problems).stream().filter(ImporterPluginScanner::isJar).toList()
                    : List.of(unit);
        } catch (RuntimeException e) {
            problems.add(new PluginProblem(unit.toString(), "Unreadable plugin: " + e.getMessage(), true));
            return List.of();
        }
        if (jars.isEmpty()) {
            problems.add(new PluginProblem(unit.toString(), "No jar in this plugin directory.", true));
            return List.of();
        }
        for (Path jar : jars) {
            try (JarFile ignored = new JarFile(jar.toFile())) {
                // readable
            } catch (IOException e) {
                problems.add(new PluginProblem(unit.toString(), "Not a valid jar file: " + jar.getFileName(), true));
                return List.of();
            }
        }

        URLClassLoader loader;
        try {
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            loader = new URLClassLoader("importer-plugin:" + unit.getFileName(), urls, parent);
        } catch (MalformedURLException e) {
            problems.add(new PluginProblem(unit.toString(), "Invalid path: " + e.getMessage(), true));
            return List.of();
        }

        String version = version(jars);
        List<PluginImporter> plugins = new ArrayList<>();
        try {
            Iterator<RdfConverterProvider> it = ServiceLoader.load(RdfConverterProvider.class, loader).iterator();
            for (int i = 0; i < MAX_PROVIDERS_PER_PLUGIN; i++) {
                RdfConverterProvider provider;
                try {
                    if (!it.hasNext()) break;
                    provider = it.next();
                } catch (ServiceConfigurationError | LinkageError e) {
                    problems.add(new PluginProblem(unit.toString(), "Cannot load an importer: " + describe(e), true));
                    continue;
                }
                // ServiceLoader also sees the providers of the parent class loader: keep this plugin's own.
                if (provider.getClass().getClassLoader() != loader) continue;
                try {
                    PluginImporter plugin = new PluginImporter(provider, loader, origin, version, unit);
                    if (plugin.id() == null || plugin.id().isBlank()) {
                        problems.add(new PluginProblem(unit.toString(), provider.getClass().getName()
                                + " has no name.", true));
                    } else if (key(plugin.id()).equals(RdfFileImporter.ID)) {
                        problems.add(new PluginProblem(unit.toString(), "\"" + RdfFileImporter.ID
                                + "\" is reserved; the plugin cannot use this name.", true));
                    } else {
                        plugins.add(plugin);
                    }
                } catch (RuntimeException | LinkageError e) {
                    problems.add(new PluginProblem(unit.toString(), provider.getClass().getName()
                            + " cannot be used: " + describe(e), true));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            problems.add(new PluginProblem(unit.toString(), "Cannot read the plugin: " + describe(e), true));
        }

        if (plugins.isEmpty()) {
            if (problems.stream().noneMatch(p -> p.location().equals(unit.toString()))) {
                problems.add(new PluginProblem(unit.toString(), "No importer (" + RdfConverterProvider.class.getName()
                        + " service) in this plugin.", true));
            }
            closeQuietly(loader);
        } else {
            loaders.add(loader);
        }
        return plugins;
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The version in the first jar's Maven {@code pom.properties}, else its {@code Implementation-Version}. */
    static String version(List<Path> jars) {
        Path jar = jars.get(0);
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    Properties props = new Properties();
                    try (InputStream in = file.getInputStream(entry)) {
                        props.load(in);
                    }
                    if (props.getProperty("version") != null) return props.getProperty("version");
                }
            }
            return file.getManifest() == null ? null
                    : file.getManifest().getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }

    static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
