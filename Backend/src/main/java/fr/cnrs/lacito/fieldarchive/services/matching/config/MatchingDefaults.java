package fr.cnrs.lacito.fieldarchive.services.matching.config;

public record MatchingDefaults(double threshold,
                               double minimumComparedWeight,
                               int maxProposalsPerEntity,
                               boolean compareUntypedEntities,
                               boolean includeSubclasses,
                               long blockingMinimumPairs) {
}
