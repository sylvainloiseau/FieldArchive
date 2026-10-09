package fr.cnrs.lacito.fieldarchive.services.matching.config;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The effective matching configuration used by the algorithm, compiled from a valid
 * {@link MatchingConfigurationDto} by {@link MatchingConfigurationLoader#compile}.
 */
public record MatchingConfiguration(MatchingDefaults defaults,
                                    List<TypeGroup> groups,
                                    String hash,
                                    MatchingConfigurationDto source) {

    public Optional<TypeGroup> group(String id) {
        return groups.stream().filter(g -> g.id().equals(id)).findFirst();
    }

    /** Every predicate of every concept, in configuration order. */
    public Set<String> allPredicates() {
        Set<String> out = new LinkedHashSet<>();
        for (TypeGroup g : groups) {
            for (MatchingConcept c : g.concepts()) out.addAll(c.predicates());
        }
        return out;
    }
}
