package fr.cnrs.lacito.fieldarchive.services.importer.testplugins;

/** Packed in a jar of its own, next to the one of {@link SampleProviders.HelperBacked}. */
public final class PluginHelper {

    private PluginHelper() {
    }

    public static String description() {
        return "From the helper jar";
    }
}
