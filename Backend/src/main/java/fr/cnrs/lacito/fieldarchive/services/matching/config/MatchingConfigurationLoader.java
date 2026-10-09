package fr.cnrs.lacito.fieldarchive.services.matching.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConceptDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingDefaultsDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.TypeGroupDto;
import fr.cnrs.lacito.fieldarchive.utils.ProjectJsonFile;
import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Reads a matching configuration (bundled default, project file, imported file or API body)
 * into its full form, and compiles it into the runtime {@link MatchingConfiguration}.
 *
 * <p>Accepted on read, so that the notebook's {@code ENTITY_EQUIVALENCES} loads unchanged:
 * a concept written as a bare array of predicates (shorthand for comparator {@code auto},
 * weight 1), a group without {@code id} (derived from {@code groupLabel}), and CURIEs
 * expanded with {@code prefixes} (plus a few well-known prefixes). Unknown fields are ignored.</p>
 */
public final class MatchingConfigurationLoader {

    public static final int SUPPORTED_FORMAT_VERSION = 1;

    /** Prefixes usable in CURIEs even when the file does not declare them. */
    public static final Map<String, String> IMPLICIT_PREFIXES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        m.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        m.put("owl", "http://www.w3.org/2002/07/owl#");
        m.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        m.put("dcterms", "http://purl.org/dc/terms/");
        m.put("skos", "http://www.w3.org/2004/02/skos/core#");
        m.put("foaf", "http://xmlns.com/foaf/0.1/");
        m.put("rico", RdfNamespaces.RICO);
        IMPLICIT_PREFIXES = Collections.unmodifiableMap(m);
    }

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private MatchingConfigurationLoader() {}

    // =========================
    //  Reading
    // =========================

    /** Parses JSON text. @throws IllegalArgumentException when it is not a configuration object. */
    public static MatchingConfigurationDto parse(String json) {
        try {
            return parse(ProjectJsonFile.MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage());
        }
    }

    public static MatchingConfigurationDto parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A matching configuration must be a JSON object.");
        }
        MatchingConfigurationDto dto = new MatchingConfigurationDto();
        dto.formatVersion = root.path("formatVersion").asInt(SUPPORTED_FORMAT_VERSION);

        JsonNode prefixes = root.path("prefixes");
        if (prefixes.isObject()) {
            for (Map.Entry<String, JsonNode> e : prefixes.properties()) {
                if (e.getValue().isTextual()) dto.prefixes.put(e.getKey(), e.getValue().asText());
            }
        }

        JsonNode defaults = root.path("defaults");
        if (defaults.isObject()) {
            MatchingDefaultsDto d = dto.defaults;
            d.threshold = doubleOr(defaults, "threshold", d.threshold);
            d.minimumComparedWeight = doubleOr(defaults, "minimumComparedWeight", d.minimumComparedWeight);
            if (defaults.hasNonNull("maxProposalsPerEntity")) d.maxProposalsPerEntity = defaults.get("maxProposalsPerEntity").asInt();
            if (defaults.hasNonNull("compareUntypedEntities")) d.compareUntypedEntities = defaults.get("compareUntypedEntities").asBoolean();
            if (defaults.hasNonNull("includeSubclasses")) d.includeSubclasses = defaults.get("includeSubclasses").asBoolean();
            if (defaults.hasNonNull("blockingMinimumPairs")) d.blockingMinimumPairs = defaults.get("blockingMinimumPairs").asLong();
        }

        JsonNode groups = root.path("equivalentGroups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                if (g.isObject()) dto.equivalentGroups.add(parseGroup(g));
            }
        }
        return normalize(dto);
    }

    private static TypeGroupDto parseGroup(JsonNode g) {
        TypeGroupDto group = new TypeGroupDto();
        group.id = textOrNull(g, "id");
        group.groupLabel = textOrNull(g, "groupLabel");
        group.note = textOrNull(g, "note");
        group.equivalentTypes = textList(g.path("equivalentTypes"));
        group.threshold = g.hasNonNull("threshold") ? g.get("threshold").asDouble() : null;
        group.minimumComparedWeight = g.hasNonNull("minimumComparedWeight") ? g.get("minimumComparedWeight").asDouble() : null;
        group.requiredConcepts = textList(g.path("requiredConcepts"));
        JsonNode properties = g.path("properties");
        if (properties.isObject()) {
            for (Map.Entry<String, JsonNode> e : properties.properties()) {
                group.properties.put(e.getKey(), parseConcept(e.getValue()));
            }
        }
        return group;
    }

    private static MatchingConceptDto parseConcept(JsonNode node) {
        MatchingConceptDto c = new MatchingConceptDto();
        if (node.isArray()) {                       // shorthand: the notebook format
            c.predicates = textList(node);
            return c;
        }
        if (node.isTextual()) {
            c.predicates = new ArrayList<>(List.of(node.asText()));
            return c;
        }
        c.predicates = textList(node.path("predicates"));
        if (node.hasNonNull("comparator")) c.comparator = node.get("comparator").asText();
        if (node.hasNonNull("weight")) c.weight = node.get("weight").asDouble();
        if (node.hasNonNull("blocking")) c.blocking = node.get("blocking").asBoolean();
        JsonNode options = node.path("options");
        if (options.isObject()) {
            for (Map.Entry<String, JsonNode> e : options.properties()) {
                JsonNode v = e.getValue();
                if (v.isBoolean()) c.options.put(e.getKey(), v.asBoolean());
                else if (v.isNumber()) c.options.put(e.getKey(), v.asDouble());
                else if (!v.isNull()) c.options.put(e.getKey(), v.asText());
            }
        }
        return c;
    }

    /**
     * Brings a configuration (parsed, or received from the API) to its canonical full form:
     * ids filled in, CURIEs expanded, null collections replaced by empty ones. Never fails;
     * what cannot be fixed is left for the validator.
     */
    public static MatchingConfigurationDto normalize(MatchingConfigurationDto dto) {
        if (dto.prefixes == null) dto.prefixes = new LinkedHashMap<>();
        if (dto.defaults == null) dto.defaults = new MatchingDefaultsDto();
        if (dto.equivalentGroups == null) dto.equivalentGroups = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (TypeGroupDto g : dto.equivalentGroups) {
            if (g.id != null && !g.id.isBlank()) usedIds.add(g.id);
        }
        for (TypeGroupDto g : dto.equivalentGroups) {
            if (g.id == null || g.id.isBlank()) {
                String base = slug(g.groupLabel);
                String id = base;
                for (int i = 2; usedIds.contains(id); i++) id = base + "-" + i;
                g.id = id;
                usedIds.add(id);
            }
            g.equivalentTypes = expandAll(g.equivalentTypes, dto.prefixes);
            if (g.requiredConcepts == null) g.requiredConcepts = new ArrayList<>();
            if (g.properties == null) g.properties = new LinkedHashMap<>();
            for (MatchingConceptDto c : g.properties.values()) {
                if (c == null) continue;
                c.predicates = expandAll(c.predicates, dto.prefixes);
                if (c.comparator == null || c.comparator.isBlank()) c.comparator = ComparatorKind.AUTO.jsonName();
                if (c.weight == null) c.weight = 1.0;
                if (c.blocking == null) c.blocking = false;
                if (c.options == null) c.options = new LinkedHashMap<>();
            }
        }
        return dto;
    }

    // =========================
    //  IRIs
    // =========================

    public static boolean isAbsoluteIri(String s) {
        return s != null && (s.contains("://") || s.startsWith("urn:"));
    }

    /** Expands a CURIE with the declared prefixes, then the implicit ones; anything else unchanged. */
    public static String expand(String iriOrCurie, Map<String, String> prefixes) {
        if (iriOrCurie == null) return null;
        String s = iriOrCurie.trim();
        if (isAbsoluteIri(s)) return s;
        int colon = s.indexOf(':');
        if (colon <= 0) return s;
        String prefix = s.substring(0, colon);
        String ns = prefixes != null ? prefixes.get(prefix) : null;
        if (ns == null) ns = IMPLICIT_PREFIXES.get(prefix);
        return ns == null ? s : ns + s.substring(colon + 1);
    }

    private static List<String> expandAll(List<String> values, Map<String, String> prefixes) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(expand(v, prefixes));
        }
        return out;
    }

    /** Lowercase ASCII slug: "Organization / Group" → "organization-group". */
    public static String slug(String label) {
        String s = TextNormalizer.fold(label == null ? "" : label).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "group" : s;
    }

    // =========================
    //  Compilation and hash
    // =========================

    /** SHA-256 of the canonical serialisation (sorted keys, full IRIs). */
    public static String hash(MatchingConfigurationDto dto) {
        try {
            byte[] bytes = CANONICAL.writeValueAsString(dto).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runtime form of a configuration that passed validation (no errors). */
    public static MatchingConfiguration compile(MatchingConfigurationDto dto) {
        MatchingDefaultsDto d = dto.defaults;
        MatchingDefaults defaults = new MatchingDefaults(
                orDefault(d.threshold, 0.75),
                orDefault(d.minimumComparedWeight, 2.0),
                d.maxProposalsPerEntity != null ? d.maxProposalsPerEntity : 5,
                d.compareUntypedEntities != null && d.compareUntypedEntities,
                d.includeSubclasses == null || d.includeSubclasses,
                d.blockingMinimumPairs != null ? d.blockingMinimumPairs : 250_000L);
        List<TypeGroup> groups = new ArrayList<>();
        int order = 0;
        for (TypeGroupDto g : dto.equivalentGroups) {
            List<MatchingConcept> concepts = new ArrayList<>();
            g.properties.forEach((key, c) -> concepts.add(new MatchingConcept(
                    key,
                    List.copyOf(c.predicates),
                    ComparatorKind.fromJson(c.comparator).orElse(ComparatorKind.AUTO),
                    c.weight,
                    c.blocking,
                    Map.copyOf(c.options))));
            groups.add(new TypeGroup(
                    g.id,
                    g.groupLabel,
                    order++,
                    List.copyOf(g.equivalentTypes),
                    g.threshold != null ? g.threshold : defaults.threshold(),
                    g.minimumComparedWeight != null ? g.minimumComparedWeight : defaults.minimumComparedWeight(),
                    Set.copyOf(g.requiredConcepts),
                    List.copyOf(concepts)));
        }
        return new MatchingConfiguration(defaults, List.copyOf(groups), hash(dto), dto);
    }

    /** Deep copy through JSON. */
    public static MatchingConfigurationDto copy(MatchingConfigurationDto dto) {
        return ProjectJsonFile.MAPPER.convertValue(dto, MatchingConfigurationDto.class);
    }

    // =========================
    //  JSON helpers
    // =========================

    private static double orDefault(Double v, double d) {
        return v != null ? v : d;
    }

    private static Double doubleOr(JsonNode node, String field, Double fallback) {
        return node.hasNonNull(field) ? Double.valueOf(node.get(field).asDouble()) : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) if (n.isTextual()) out.add(n.asText());
        } else if (node.isTextual()) {
            out.add(node.asText());
        }
        return out;
    }
}
