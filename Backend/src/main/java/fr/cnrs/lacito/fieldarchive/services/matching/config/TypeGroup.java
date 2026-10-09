package fr.cnrs.lacito.fieldarchive.services.matching.config;

import java.util.List;
import java.util.Set;

/**
 * A group of equivalent classes (runtime form). {@code order} is the position in the
 * configuration, used to break ties between groups.
 */
public record TypeGroup(String id,
                        String label,
                        int order,
                        List<String> equivalentTypes,
                        double threshold,
                        double minimumComparedWeight,
                        Set<String> requiredConcepts,
                        List<MatchingConcept> concepts) {
}
