package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.services.importer.testplugins.SampleProviders;
import fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds plugin jars from the compiled test classes of {@code testplugins}. */
final class PluginJars {

    static final String TEST_PLUGINS = SampleProviders.class.getPackageName();

    private PluginJars() {
    }

    /** A jar holding {@code classes} (with their nested classes) and a service file listing {@code providers}. */
    static Path jar(Path target, Class<?>[] classes, Class<?>... providers) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(out)) {
            for (Class<?> c : classes) {
                String path = c.getName().replace('.', '/') + ".class";
                add(jar, path, c);
            }
            if (providers.length > 0) {
                StringBuilder services = new StringBuilder();
                for (Class<?> p : providers) services.append(p.getName()).append('\n');
                jar.putNextEntry(new JarEntry("META-INF/services/" + RdfConverterProvider.class.getName()));
                jar.write(services.toString().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return target;
    }

    private static void add(JarOutputStream jar, String path, Class<?> anchor) throws IOException {
        try (InputStream in = anchor.getClassLoader().getResourceAsStream(path)) {
            jar.putNextEntry(new JarEntry(path));
            in.transferTo(jar);
            jar.closeEntry();
        }
    }

    /** The test class loader, without the classes of {@code testplugins}: plugins must come from their jars. */
    static ClassLoader parentHidingTestPlugins() {
        ClassLoader app = PluginJars.class.getClassLoader();
        return new ClassLoader(app) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(TEST_PLUGINS + ".")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
    }
}
