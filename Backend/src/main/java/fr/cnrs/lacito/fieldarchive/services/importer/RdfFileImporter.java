package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.ricimporterplugin.InputKind;
import fr.cnrs.lacito.ricimporterplugin.OptionDescriptor;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "Sync with an RDF file": parses an RDF file in any format known to RDF4J, chosen from the
 * file name (Turtle when the extension says nothing).
 */
public final class RdfFileImporter implements Importer {

    public static final String ID = "rdf-file";
    public static final String LABEL = "Sync with an RDF file";

    @Override public String id() { return ID; }
    @Override public String label() { return LABEL; }
    @Override public String description() { return "Reads an RDF file (Turtle, RDF/XML, N-Triples, TriG, JSON-LD…)"; }
    @Override public List<OptionDescriptor> options() { return List.of(); }
    @Override public InputKind inputKind() { return InputKind.FILE; }
    @Override public ImporterOrigin origin() { return ImporterOrigin.BUILT_IN; }
    @Override public String version() { return null; }
    @Override public Path location() { return null; }

    /** The parser format for a file name, Turtle by default. */
    public static RDFFormat formatFor(String fileName) {
        return fileName == null ? RDFFormat.TURTLE
                : Rio.getParserFormatForFileName(fileName).orElse(RDFFormat.TURTLE);
    }

    @Override
    public void emit(ImportInput input, ImportSettings settings, RDFHandler handler) {
        String fileName = input.fileName() != null ? input.fileName()
                : input.path() != null && input.path().getFileName() != null ? input.path().getFileName().toString()
                : null;
        RDFParser parser = Rio.createParser(formatFor(fileName));
        parser.getParserConfig().set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);
        parser.setRDFHandler(handler);
        String base = settings.baseIri() == null ? "" : settings.baseIri();

        if (input.stream() != null) {
            parse(parser, input.stream(), base);
            return;
        }
        Path path = input.path();
        if (Files.isDirectory(path)) {
            throw new BadRequestException("An RDF file is expected, not a directory: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            parse(parser, in, base);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable RDF file " + path + ": " + e.getMessage(), e);
        }
    }

    private static void parse(RDFParser parser, InputStream in, String base) {
        try {
            parser.parse(in, base);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
