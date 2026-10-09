package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchLink;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingRunSummaryDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The on-disk shape of {@code <project dir>/data_matching.json}. */
public class MatchingFile {
    public int formatVersion = 1;
    /** Last time a confirmed link was undone, rejected or removed: pairs run before are stale. */
    public String confirmedLinksRevokedAt;
    /** Last run of each data-source pair, keyed by {@code DataSourcePair.key()}. */
    public Map<String, MatchingRunSummaryDto> runs = new LinkedHashMap<>();
    public List<MatchLink> links = new ArrayList<>();
}
