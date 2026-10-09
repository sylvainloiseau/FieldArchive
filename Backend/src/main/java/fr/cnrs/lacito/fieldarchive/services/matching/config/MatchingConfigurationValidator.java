package fr.cnrs.lacito.fieldarchive.services.matching.config;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConceptDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDefaultsDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.TypeGroupDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Checks a (normalised) configuration. Errors make it unusable and block saving; warnings
 * point at choices that are legal but probably not intended. Warnings about the project's
 * data need a {@link DataContext}; without one they are skipped.
 */
public final class MatchingConfigurationValidator {

    private static final Pattern GROUP_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern CONCEPT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /** What the validator may know about the open project. Any method may return null (unknown). */
    public interface DataContext {
        /** Types declared by the DefinedOntologies. */
        Set<String> definedTypes();
        /** Types used in the data graphs. */
        Set<String> usedTypes();
        /** Predicates used in the data graphs. */
        Set<String> usedPredicates();
        /** Group ids referenced by stored links. */
        Set<String> referencedGroupIds();
    }

    private MatchingConfigurationValidator() {}

    public static ValidationReportDto validate(MatchingConfigurationDto dto, DataContext data) {
        ValidationReportDto r = new ValidationReportDto();
        if (dto.formatVersion > MatchingConfigurationLoader.SUPPORTED_FORMAT_VERSION || dto.formatVersion < 1) {
            r.error("formatVersion", "UNSUPPORTED_VERSION",
                    "Unsupported format version " + dto.formatVersion + " (this application reads version "
                            + MatchingConfigurationLoader.SUPPORTED_FORMAT_VERSION + ").");
        }
        validateDefaults(dto.defaults, r);

        Set<String> ids = new HashSet<>();
        Map<String, String> typeToGroup = new HashMap<>();
        for (int i = 0; i < dto.equivalentGroups.size(); i++) {
            TypeGroupDto g = dto.equivalentGroups.get(i);
            String path = "equivalentGroups[" + i + "]";
            if (g == null) {
                r.error(path, "NULL_GROUP", "Empty group.");
                continue;
            }
            validateGroup(g, path, dto, r, data);
            if (g.id != null && !ids.add(g.id)) {
                r.error(path + ".id", "DUPLICATE_ID", "The id \"" + g.id + "\" is used by two groups.");
            }
            for (String t : g.equivalentTypes) {
                String other = typeToGroup.putIfAbsent(t, label(g));
                if (other != null && !other.equals(label(g))) {
                    r.warning(path + ".equivalentTypes", "TYPE_IN_TWO_GROUPS",
                            t + " is also declared in group \"" + other + "\"; the most specific group is used.");
                }
            }
        }
        if (data != null && data.referencedGroupIds() != null) {
            for (String referenced : data.referencedGroupIds()) {
                if (referenced != null && !ids.contains(referenced)) {
                    r.warning("equivalentGroups", "REFERENCED_GROUP_REMOVED",
                            "Stored links refer to the group id \"" + referenced + "\", which no longer exists.");
                }
            }
        }
        return r;
    }

    private static void validateDefaults(MatchingDefaultsDto d, ValidationReportDto r) {
        if (d == null) return;
        checkUnit(d.threshold, "defaults.threshold", r);
        if (d.minimumComparedWeight != null && d.minimumComparedWeight < 0) {
            r.error("defaults.minimumComparedWeight", "OUT_OF_RANGE", "Must be ≥ 0.");
        }
        if (d.maxProposalsPerEntity != null && (d.maxProposalsPerEntity < 1 || d.maxProposalsPerEntity > 50)) {
            r.error("defaults.maxProposalsPerEntity", "OUT_OF_RANGE", "Must be between 1 and 50.");
        }
        if (d.blockingMinimumPairs != null && d.blockingMinimumPairs < 0) {
            r.error("defaults.blockingMinimumPairs", "OUT_OF_RANGE", "Must be ≥ 0.");
        }
    }

    private static void validateGroup(TypeGroupDto g, String path, MatchingConfigurationDto dto,
                                      ValidationReportDto r, DataContext data) {
        if (g.id == null || !GROUP_ID.matcher(g.id).matches()) {
            r.error(path + ".id", "INVALID_ID", "The id must be lowercase letters, digits and dashes.");
        }
        if (g.groupLabel == null || g.groupLabel.isBlank()) {
            r.error(path + ".groupLabel", "REQUIRED", "The group needs a label.");
        }
        if (g.equivalentTypes.isEmpty()) {
            r.error(path + ".equivalentTypes", "REQUIRED", "The group needs at least one type.");
        }
        for (String t : g.equivalentTypes) {
            if (!MatchingConfigurationLoader.isAbsoluteIri(t)) {
                r.error(path + ".equivalentTypes", "INVALID_IRI",
                        "\"" + t + "\" is neither an IRI nor a CURIE with a declared prefix.");
            } else if (data != null && data.definedTypes() != null && data.usedTypes() != null
                    && !data.definedTypes().contains(t) && !data.usedTypes().contains(t)) {
                r.warning(path + ".equivalentTypes", "UNKNOWN_TYPE",
                        t + " is not defined by a known ontology and not used in the project's data.");
            }
        }
        checkUnit(g.threshold, path + ".threshold", r);
        if (g.minimumComparedWeight != null && g.minimumComparedWeight < 0) {
            r.error(path + ".minimumComparedWeight", "OUT_OF_RANGE", "Must be ≥ 0.");
        }
        if (g.properties.isEmpty()) {
            r.error(path + ".properties", "REQUIRED", "The group needs at least one compared concept.");
        }
        for (String required : g.requiredConcepts) {
            if (!g.properties.containsKey(required)) {
                r.error(path + ".requiredConcepts", "UNKNOWN_CONCEPT", "No concept named \"" + required + "\".");
            }
        }

        double weightSum = 0;
        for (Map.Entry<String, MatchingConceptDto> e : g.properties.entrySet()) {
            String cpath = path + ".properties." + e.getKey();
            MatchingConceptDto c = e.getValue();
            if (!CONCEPT_KEY.matcher(e.getKey()).matches()) {
                r.error(cpath, "INVALID_KEY", "A concept name starts with a letter and contains only letters, digits and _.");
            }
            if (c == null) {
                r.error(cpath, "NULL_CONCEPT", "Empty concept.");
                continue;
            }
            if (c.predicates.isEmpty()) {
                r.error(cpath + ".predicates", "REQUIRED", "The concept needs at least one predicate.");
            }
            for (String p : c.predicates) {
                if (!MatchingConfigurationLoader.isAbsoluteIri(p)) {
                    r.error(cpath + ".predicates", "INVALID_IRI",
                            "\"" + p + "\" is neither an IRI nor a CURIE with a declared prefix.");
                } else if (data != null && data.usedPredicates() != null && !data.usedPredicates().contains(p)) {
                    r.warning(cpath + ".predicates", "UNUSED_PREDICATE",
                            MatchingConfigurationWriter.compact(p, dto.prefixes) + " is not used in the project's data.");
                }
            }
            Optional<ComparatorKind> kind = ComparatorKind.fromJson(c.comparator);
            if (kind.isEmpty()) {
                r.error(cpath + ".comparator", "UNKNOWN_COMPARATOR", "Unknown comparator \"" + c.comparator + "\".");
            } else {
                validateOptions(kind.get(), c.options, cpath + ".options", r);
                if (Boolean.TRUE.equals(c.blocking)
                        && (kind.get() == ComparatorKind.TEXT || kind.get() == ComparatorKind.LINKED_ENTITY)) {
                    r.warning(cpath + ".blocking", "BLOCKING_IGNORED",
                            "Blocking keys are not computed for the " + c.comparator + " comparator.");
                }
            }
            if (c.weight == null || c.weight <= 0 || c.weight > 100) {
                r.error(cpath + ".weight", "OUT_OF_RANGE", "The weight must be > 0 and ≤ 100.");
            } else {
                weightSum += c.weight;
            }
        }
        double minWeight = g.minimumComparedWeight != null ? g.minimumComparedWeight
                : dto.defaults.minimumComparedWeight != null ? dto.defaults.minimumComparedWeight : 0;
        if (!g.properties.isEmpty() && minWeight > weightSum) {
            r.warning(path + ".minimumComparedWeight", "UNREACHABLE_EVIDENCE",
                    "The minimum evidence (" + minWeight + ") exceeds the sum of the weights (" + weightSum
                            + "): this group can never propose a match.");
        }
    }

    private static void validateOptions(ComparatorKind kind, Map<String, Object> options, String path, ValidationReportDto r) {
        for (Map.Entry<String, Object> o : options.entrySet()) {
            ComparatorKind.OptionType type = kind.options().get(o.getKey());
            if (type == null) {
                r.error(path + "." + o.getKey(), "UNKNOWN_OPTION",
                        "The " + kind.jsonName() + " comparator has no option \"" + o.getKey() + "\".");
                continue;
            }
            Object v = o.getValue();
            boolean ok = switch (type) {
                case BOOLEAN -> v instanceof Boolean;
                case UNIT_INTERVAL -> v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 1;
                case NON_NEGATIVE -> v instanceof Number n && n.doubleValue() >= 0;
            };
            if (!ok) {
                String expected = switch (type) {
                    case BOOLEAN -> "true or false";
                    case UNIT_INTERVAL -> "a number between 0 and 1";
                    case NON_NEGATIVE -> "a number ≥ 0";
                };
                r.error(path + "." + o.getKey(), "INVALID_OPTION", "Expected " + expected + ".");
            }
        }
    }

    private static void checkUnit(Double v, String path, ValidationReportDto r) {
        if (v != null && (v < 0 || v > 1)) r.error(path, "OUT_OF_RANGE", "Must be between 0 and 1.");
    }

    private static String label(TypeGroupDto g) {
        return g.groupLabel != null ? g.groupLabel : String.valueOf(g.id);
    }
}
