package fr.cnrs.lacito.fieldarchive.services.nlquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.SparqlService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.LlmProvider.ToolSpec;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The read-only tools of the natural-language agents. Definitions (names, descriptions,
 * parameter schemas) are in {@code nlquery/tools.json}, shared by every provider.
 */
@Component
public class NlQueryTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HAS_LIMIT = Pattern.compile("(?i)\\blimit\\s+\\d+");

    private final RdfEntityService rdfEntityService;
    private final SparqlService sparqlService;
    private final RicoDigestService ricoDigest;
    private final UsageProfileService usageProfile;
    private final NlQueryHistoryService history;

    private final List<ToolSpec> specs = new ArrayList<>();
    private final Map<String, JsonNode> schemaByName = new HashMap<>();
    private final JsonNode outputSchema;

    public NlQueryTools(RdfEntityService rdfEntityService, SparqlService sparqlService, RicoDigestService ricoDigest,
                        UsageProfileService usageProfile, NlQueryHistoryService history) {
        this.rdfEntityService = rdfEntityService;
        this.sparqlService = sparqlService;
        this.ricoDigest = ricoDigest;
        this.usageProfile = usageProfile;
        this.history = history;
        try (InputStream tools = new ClassPathResource("nlquery/tools.json").getInputStream();
             InputStream output = new ClassPathResource("nlquery/output-schema.json").getInputStream()) {
            for (JsonNode t : JSON.readTree(tools)) {
                ToolSpec spec = new ToolSpec(t.get("name").asText(), t.get("description").asText(), t.get("parameters"));
                specs.add(spec);
                schemaByName.put(spec.name(), spec.parametersSchema());
            }
            outputSchema = JSON.readTree(output);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read nlquery/tools.json or output-schema.json", e);
        }
    }

    public List<ToolSpec> specs() { return specs; }

    public JsonNode outputSchema() { return outputSchema; }

    /** Result of one tool call: the text for the model, a one-line trace for the user. */
    public record Outcome(String content, boolean isError, String trace) {}

    public Outcome run(String name, String argumentsJson, int maxChars, Map<String, String> prefixes) {
        JsonNode schema = schemaByName.get(name);
        if (schema == null) return new Outcome("Unknown tool: " + name, true, name + " → unknown tool");
        JsonNode args;
        try {
            args = JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return new Outcome("Invalid JSON arguments: " + e.getMessage(), true, name + " → invalid arguments");
        }
        List<String> problems = SchemaValidator.validate(args, schema);
        if (!problems.isEmpty()) {
            return new Outcome("Invalid arguments: " + String.join("; ", problems), true, name + " → invalid arguments");
        }
        try {
            return switch (name) {
                case "search_entities" -> searchEntities(args.get("text").asText(), args.get("type").asText(), maxChars, prefixes);
                case "describe_entity" -> describeEntity(args.get("iri").asText(), maxChars, prefixes);
                case "run_select" -> runSelect(args.get("query").asText(), maxChars);
                case "search_ontology" -> searchOntology(args.get("text").asText(), maxChars);
                case "find_usage" -> findUsage(args.get("text_or_property").asText(), args.get("type").asText(), maxChars);
                case "search_history" -> searchHistory(args.get("text").asText(), maxChars);
                default -> new Outcome("Unknown tool: " + name, true, name + " → unknown tool");
            };
        } catch (BadRequestException e) {
            return new Outcome(e.getMessage(), true, name + " → error: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome("Tool failed: " + e.getMessage(), true, name + " → error");
        }
    }

    private static String trim(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "\n… (truncated)";
    }

    private static String quote(String s) {
        return "\"" + (s.length() > 60 ? s.substring(0, 60) + "…" : s) + "\"";
    }

    private Outcome searchEntities(String text, String type, int maxChars, Map<String, String> prefixes) {
        String typeIri = type == null || type.isBlank() ? null : Prefixes.expand(type, prefixes);
        List<EntityMatchDto> matches = rdfEntityService.searchByLabel(text, typeIri, 20);
        StringBuilder sb = new StringBuilder();
        if (matches.isEmpty()) sb.append("No entity matches ").append(quote(text)).append('.');
        for (EntityMatchDto m : matches) {
            List<String> types = new ArrayList<>();
            for (String t : m.types) types.add(Prefixes.curie(t, prefixes));
            sb.append('<').append(m.iri).append(">  \"").append(m.label).append("\"  types: ").append(String.join(", ", types))
                    .append("  source: ").append("internal".equals(m.source) ? "internal (editable)"
                            : "external" + (m.datasourceShortName != null ? " (" + m.datasourceShortName + ", read-only)" : " (read-only)"))
                    .append("  score: ").append(m.score).append('\n');
        }
        String trace = "search_entities(" + quote(text) + (typeIri != null ? ", " + Prefixes.curie(typeIri, prefixes) : "") + ") → "
                + matches.size() + (matches.size() == 1 ? " match" : " matches");
        return new Outcome(trim(sb.toString(), maxChars), false, trace);
    }

    private Outcome describeEntity(String iri, int maxChars, Map<String, String> prefixes) {
        RdfEntityDto e;
        try {
            e = rdfEntityService.getByIri(SimpleValueFactory.getInstance().createIRI(Prefixes.expand(iri, prefixes)));
        } catch (RuntimeException ex) {
            return new Outcome("No entity <" + iri + ">.", true, "describe_entity(<" + iri + ">) → not found");
        }
        StringBuilder sb = new StringBuilder("<").append(e.iri).append(">  (").append(e.source).append(")\n");
        for (RdfTypeDto t : e.types) {
            sb.append("  rdf:type ").append(Prefixes.curie(t.iri, prefixes)).append("  [").append(t.datasourceShortName).append("]\n");
        }
        for (RdfPropertyDto p : e.properties) {
            for (RdfValueDto v : p.values) {
                String value = "iri".equals(v.kind)
                        ? "<" + v.value + ">" + (v.name != null && !v.name.isBlank() ? " (\"" + v.name + "\")" : "")
                        : "\"" + (v.value.length() > 200 ? v.value.substring(0, 200) + "…" : v.value) + "\""
                          + (v.lang != null ? "@" + v.lang : v.datatype != null && !v.datatype.endsWith("#string") ? "^^" + Prefixes.curie(v.datatype, prefixes) : "");
                sb.append("  ").append(Prefixes.curie(p.predicate, prefixes)).append(' ').append(value)
                        .append("  [").append(v.datasourceShortName).append("]\n");
            }
        }
        String label = rdfEntityService.bestLabel(SimpleValueFactory.getInstance().createIRI(e.iri));
        return new Outcome(trim(sb.toString(), maxChars), false, "describe_entity(" + (label != null ? quote(label) : "<" + e.iri + ">") + ")");
    }

    private Outcome runSelect(String query, int maxChars) {
        if (!(SparqlService.parse(query) instanceof ParsedTupleQuery)) {
            return new Outcome("run_select only runs SELECT queries.", true, "run_select → rejected (not a SELECT)");
        }
        String q = HAS_LIMIT.matcher(query).find() ? query : query + "\nLIMIT 50";
        List<Map<String, String>> rows = sparqlService.select(q, 10);
        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) sb.append("No results.");
        else sb.append(String.join("\t", rows.get(0).keySet())).append('\n');
        for (Map<String, String> row : rows.subList(0, Math.min(50, rows.size()))) {
            List<String> cells = new ArrayList<>();
            for (String v : row.values()) cells.add(v == null ? "" : v.length() > 150 ? v.substring(0, 150) + "…" : v);
            sb.append(String.join("\t", cells)).append('\n');
        }
        return new Outcome(trim(sb.toString(), maxChars), false, "run_select → " + rows.size() + (rows.size() == 1 ? " row" : " rows"));
    }

    private Outcome searchOntology(String text, int maxChars) {
        List<RicoDigestService.Entry> entries = ricoDigest.search(text, 15);
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) sb.append("Nothing in RiC-O matches ").append(quote(text)).append('.');
        for (RicoDigestService.Entry e : entries) sb.append(e.text);
        String first = entries.isEmpty() ? "" : " (" + entries.get(0).curie + (entries.size() > 1 ? ", …" : "") + ")";
        return new Outcome(trim(sb.toString(), maxChars), false,
                "search_ontology(" + quote(text) + ") → " + entries.size() + " RiC-O terms" + first);
    }

    private Outcome findUsage(String textOrProperty, String type, int maxChars) {
        String out = usageProfile.findUsage(textOrProperty, type);
        return new Outcome(trim(out, maxChars), false, "find_usage(" + quote(textOrProperty) + ")");
    }

    private Outcome searchHistory(String text, int maxChars) {
        List<NlHistoryEntry> entries = history.search(text, 10);
        String out = entries.isEmpty() ? "No history entry matches " + quote(text) + "." : history.render(entries);
        return new Outcome(trim(out, maxChars), false, "search_history(" + quote(text) + ") → " + entries.size() + " entries");
    }
}
