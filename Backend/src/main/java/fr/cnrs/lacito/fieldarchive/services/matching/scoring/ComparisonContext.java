package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;

import java.util.function.BiPredicate;

/**
 * What a comparator may need besides the two values: the concept (for its options) and
 * whether two IRIs are already known to denote the same entity.
 */
public record ComparisonContext(MatchingConcept concept, BiPredicate<String, String> sameEntity) {
}
