package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a matching run on a pair of data sources did. Also stored, for the last run of each pair. */
public class MatchingRunSummaryDto {
    public String dataSourceA;
    public String dataSourceB;
    public String startedAt;
    public long durationMs;
    public int algorithmVersion;
    public String configurationHash;
    public int entityCountA;
    public int entityCountB;
    public int notInAnyGroupA;
    public int notInAnyGroupB;
    public int untypedA;
    public int untypedB;
    /** Entities skipped because they already have a counterpart in the other source. */
    public int excludedMatchedA;
    public int excludedMatchedB;
    public int identicalIriCount;
    public long possiblePairCount;
    public long comparedPairCount;
    public long excludedRejectedCount;
    public long excludedInvariantCount;
    public long belowThresholdCount;
    public long insufficientEvidenceCount;
    public int proposalCount;
    public Map<String, Integer> proposalCountByGroup = new LinkedHashMap<>();
}
