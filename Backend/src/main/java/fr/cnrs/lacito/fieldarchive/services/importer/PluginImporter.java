package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.ricimporterplugin.ConverterOptions;
import fr.cnrs.lacito.ricimporterplugin.InputKind;
import fr.cnrs.lacito.ricimporterplugin.OptionDescriptor;
import fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider;
import org.eclipse.rdf4j.rio.RDFHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * An importer provided by a plugin: an {@link RdfConverterProvider} found in a jar of a
 * {@code ricImporterPlugins} directory. Its properties are read once, when the plugin is
 * loaded, so that a misbehaving provider fails at scan time rather than in the UI.
 */
public final class PluginImporter implements Importer {

    private final RdfConverterProvider provider;
    private final ClassLoader loader;
    private final String name;
    private final String description;
    private final List<OptionDescriptor> options;
    private final InputKind inputKind;
    private final ImporterOrigin origin;
    private final String version;
    private final Path location;

    PluginImporter(RdfConverterProvider provider, ClassLoader loader, ImporterOrigin origin, String version,
                   Path location) {
        this.provider = provider;
        this.loader = loader;
        this.name = provider.name();
        this.description = provider.description();
        this.options = List.copyOf(provider.options());
        this.inputKind = provider.inputKind() == null ? InputKind.FILE_OR_DIRECTORY : provider.inputKind();
        this.origin = origin;
        this.version = version;
        this.location = location;
    }

    @Override public String id() { return name; }
    @Override public String label() { return name; }
    @Override public String description() { return description; }
    @Override public List<OptionDescriptor> options() { return options; }
    @Override public InputKind inputKind() { return inputKind; }
    @Override public ImporterOrigin origin() { return origin; }
    @Override public String version() { return version; }
    @Override public Path location() { return location; }

    ClassLoader loader() {
        return loader;
    }

    @Override
    public void emit(ImportInput input, ImportSettings settings, RDFHandler handler) {
        Path path = input.path();
        if (path == null) {
            throw new BadRequestException("The " + name + " importer reads a path on this machine, not an uploaded file.");
        }
        if (!Files.exists(path)) {
            throw new BadRequestException("Not found: " + path);
        }
        boolean directory = Files.isDirectory(path);
        if (directory && !inputKind.acceptsDirectory()) {
            throw new BadRequestException("The " + name + " importer reads a file, not a directory: " + path);
        }
        if (!directory && !inputKind.acceptsFile()) {
            throw new BadRequestException("The " + name + " importer reads a directory, not a file: " + path);
        }
        ConverterOptions converterOptions = ConverterOptions.builder(settings.baseIri())
                .setAll(settings.options())
                .build();

        // The plugin may look things up through the context class loader (ServiceLoader, Rio…).
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            provider.create(path, converterOptions).convert(handler);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
