package fr.cnrs.lacito.fieldarchive.services.importer.testplugins;

import fr.cnrs.lacito.ricimporterplugin.ConverterOptions;
import fr.cnrs.lacito.ricimporterplugin.InputKind;
import fr.cnrs.lacito.ricimporterplugin.OptionDescriptor;
import fr.cnrs.lacito.ricimporterplugin.RdfConverter;
import fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import java.nio.file.Path;
import java.util.List;

/**
 * Providers packed into jars by ImporterPluginScannerTest. They are hidden from the scanner's
 * parent class loader, so that they are loaded from the jars, as real plugins are.
 */
public final class SampleProviders {

    private SampleProviders() {
    }

    /** Emits one statement per call: {@code <base/item> rdfs:label "<option 'label'>"}. */
    public static class Alpha implements RdfConverterProvider {
        @Override public String name() { return "alpha"; }
        @Override public String description() { return "Alpha (bundled)"; }
        @Override public InputKind inputKind() { return InputKind.DIRECTORY; }
        @Override public List<OptionDescriptor> options() {
            return List.of(new OptionDescriptor("label", "Label of the item", "item"));
        }
        @Override public RdfConverter create(Path input, ConverterOptions options) {
            return handler -> {
                var vf = SimpleValueFactory.getInstance();
                handler.startRDF();
                handler.handleStatement(vf.createStatement(vf.createIRI(options.baseIri() + "/item"), RDFS.LABEL,
                        vf.createLiteral(options.get("label", "item"))));
                handler.endRDF();
            };
        }
    }

    /** Same name as {@link Alpha}, other case. */
    public static class AlphaTwin extends Alpha {
        @Override public String name() { return "ALPHA"; }
    }

    /** Same name as {@link Alpha}, for the user directory. */
    public static class UserAlpha extends Alpha {
        @Override public String description() { return "Alpha (user)"; }
    }

    public static class Reserved extends Alpha {
        @Override public String name() { return "rdf-file"; }
    }

    public static class Broken extends Alpha {
        @Override public String name() { throw new IllegalStateException("broken on purpose"); }
    }

    /** Uses a class from another jar of the same plugin directory. */
    public static class HelperBacked extends Alpha {
        @Override public String name() { return "helper"; }
        @Override public String description() { return PluginHelper.description(); }
    }
}
