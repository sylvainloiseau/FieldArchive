package fr.cnrs.lacito.fieldarchive.services.importer;

import java.util.Map;

/**
 * @param baseIri for a plugin, the prefix under which it mints the IRIs of the imported
 *                entities; for an RDF file, the base against which relative IRIs are resolved
 * @param options plugin options (name → value); empty for RDF files
 */
public record ImportSettings(String baseIri, Map<String, String> options) {

    public ImportSettings {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
