package fr.cnrs.lacito.fieldarchive.dtos.matching;

/** Values applying to every type group unless the group overrides them. */
public class MatchingDefaultsDto {
    public Double threshold = 0.75;
    public Double minimumComparedWeight = 2.0;
    public Integer maxProposalsPerEntity = 5;
    public Boolean compareUntypedEntities = false;
    public Boolean includeSubclasses = true;
    public Long blockingMinimumPairs = 250_000L;
}
