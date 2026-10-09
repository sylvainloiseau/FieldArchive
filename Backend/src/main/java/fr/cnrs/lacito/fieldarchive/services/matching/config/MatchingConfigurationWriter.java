package fr.cnrs.lacito.fieldarchive.services.matching.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConceptDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.TypeGroupDto;
import fr.cnrs.lacito.fieldarchive.utils.ProjectJsonFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serialises a configuration for {@code matching-configuration.json}: always the full form
 * (no shorthand, every group with its {@code id}), IRIs compacted to CURIEs with the declared
 * prefixes so that the file stays readable when edited by hand.
 */
public final class MatchingConfigurationWriter {

    private MatchingConfigurationWriter() {}

    public static String toFileJson(MatchingConfigurationDto dto) {
        try {
            return ProjectJsonFile.MAPPER.writeValueAsString(compacted(dto));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A copy of {@code dto} whose IRIs are CURIEs where a declared prefix applies. */
    public static MatchingConfigurationDto compacted(MatchingConfigurationDto dto) {
        MatchingConfigurationDto copy = MatchingConfigurationLoader.copy(dto);
        for (TypeGroupDto g : copy.equivalentGroups) {
            g.equivalentTypes = compactAll(g.equivalentTypes, copy.prefixes);
            for (MatchingConceptDto c : g.properties.values()) {
                if (c != null) c.predicates = compactAll(c.predicates, copy.prefixes);
            }
        }
        return copy;
    }

    /** CURIE of {@code iri} with the longest matching namespace of {@code prefixes}, else the IRI. */
    public static String compact(String iri, Map<String, String> prefixes) {
        if (iri == null || prefixes == null) return iri;
        String bestPrefix = null;
        String bestNamespace = "";
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            String ns = e.getValue();
            if (ns != null && iri.startsWith(ns) && ns.length() > bestNamespace.length()) {
                String local = iri.substring(ns.length());
                if (!local.isEmpty() && !local.contains("/") && !local.contains("#")) {
                    bestPrefix = e.getKey();
                    bestNamespace = ns;
                }
            }
        }
        return bestPrefix == null ? iri : bestPrefix + ":" + iri.substring(bestNamespace.length());
    }

    private static List<String> compactAll(List<String> iris, Map<String, String> prefixes) {
        List<String> out = new ArrayList<>();
        for (String iri : iris) out.add(compact(iri, prefixes));
        return out;
    }
}
