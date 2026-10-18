package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.ricimporterplugin.InputKind;
import fr.cnrs.lacito.ricimporterplugin.OptionDescriptor;
import org.eclipse.rdf4j.rio.RDFHandler;

import java.nio.file.Path;
import java.util.List;

/**
 * Fills an external data source: reads its input and pushes the statements into an
 * {@link RDFHandler}, which writes them into the source's graph ({@link GraphReplacingSink}).
 * Nothing is written to a file in between.
 */
public interface Importer {

    /** Stable id, stored in {@code app:sourceTool}: a plugin's name, or {@link RdfFileImporter#ID}. */
    String id();

    String label();

    String description();

    List<OptionDescriptor> options();

    InputKind inputKind();

    ImporterOrigin origin();

    /** Version of the plugin, or null. */
    String version();

    /** Jar (or plugin directory) the importer was loaded from; null for the built-in one. */
    Path location();

    /**
     * Streams the statements read from {@code input} into {@code handler}
     * ({@code startRDF} … {@code endRDF}).
     */
    void emit(ImportInput input, ImportSettings settings, RDFHandler handler);
}
