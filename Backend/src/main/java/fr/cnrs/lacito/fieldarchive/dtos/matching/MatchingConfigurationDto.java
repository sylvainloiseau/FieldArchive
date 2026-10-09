package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data matching configuration, full form: what the REST API carries (full IRIs) and, after
 * compaction to CURIEs, what {@code matching-configuration.json} contains.
 */
public class MatchingConfigurationDto {
    public int formatVersion = 1;
    public Map<String, String> prefixes = new LinkedHashMap<>();
    public MatchingDefaultsDto defaults = new MatchingDefaultsDto();
    public List<TypeGroupDto> equivalentGroups = new ArrayList<>();
}
