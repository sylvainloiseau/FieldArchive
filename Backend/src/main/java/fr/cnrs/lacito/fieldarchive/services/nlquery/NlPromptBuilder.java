package fr.cnrs.lacito.fieldarchive.services.nlquery;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.core.RdfContexts;
import fr.cnrs.lacito.fieldarchive.core.RdfNamespaces;
import fr.cnrs.lacito.fieldarchive.dtos.DataSourceDto;
import fr.cnrs.lacito.fieldarchive.dtos.NlClarification;
import fr.cnrs.lacito.fieldarchive.dtos.NlHistoryEntry;
import fr.cnrs.lacito.fieldarchive.dtos.RdfEntityDto;
import fr.cnrs.lacito.fieldarchive.dtos.RdfPropertyDto;
import fr.cnrs.lacito.fieldarchive.dtos.RdfValueDto;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import fr.cnrs.lacito.fieldarchive.services.OntologyService;
import fr.cnrs.lacito.fieldarchive.services.ProjectService;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.LlmProvider.ContextBudget;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds the three parts of an agent's input, in a fixed order so the stable parts can be
 * prompt-cached: {@code staticSystem} (instructions + RiC-O digest, identical for every project
 * and question), {@code projectSystem} (prefixes, configuration, usage profile, samples, graph
 * layout; changes only with the data) and the user message (history examples, decisions,
 * question). The local model gets a compact form (no digest, smaller profile, fewer examples).
 */
@Component
public class NlPromptBuilder {

    private final RicoDigestService ricoDigest;
    private final UsageProfileService usageProfile;
    private final NlQueryHistoryService history;
    private final OntologyService ontologyService;
    private final DataSourceService dsService;
    private final ProjectService projectService;
    private final RdfEntityService rdfEntityService;
    private final int sampleEntities;

    public NlPromptBuilder(RicoDigestService ricoDigest, UsageProfileService usageProfile, NlQueryHistoryService history,
                           OntologyService ontologyService, DataSourceService dsService, ProjectService projectService,
                           RdfEntityService rdfEntityService,
                           @Value("${fieldarchive.nlquery.sample-entities:1}") int sampleEntities) {
        this.ricoDigest = ricoDigest;
        this.usageProfile = usageProfile;
        this.history = history;
        this.ontologyService = ontologyService;
        this.dsService = dsService;
        this.projectService = projectService;
        this.rdfEntityService = rdfEntityService;
        this.sampleEntities = sampleEntities;
    }

    public record Prompt(String staticSystem, String projectSystem, String userMessage,
                         Map<String, String> prefixes, List<NlHistoryEntry> examples) {}

    public Prompt build(String question, List<NlClarification> clarifications, ContextBudget budget) {
        UsageProfileService.Profile profile = usageProfile.profile();
        boolean full = budget == ContextBudget.FULL;
        List<NlHistoryEntry> examples = history.selectExamples(question, full ? 8 : 3);
        return new Prompt(staticPart(full), projectPart(profile, full), userPart(question, clarifications, examples),
                profile.prefixes, examples);
    }

    // =========================
    //  Static part
    // =========================

    private String staticPart(boolean full) {
        List<String> labelOrder = new ArrayList<>();
        for (String p : RdfEntityService.LABEL_PREDICATES) labelOrder.add(Prefixes.curie(p, Map.of(
                "rico", RdfNamespaces.RICO, "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
                "dcterms", "http://purl.org/dc/terms/", "foaf", "http://xmlns.com/foaf/0.1/")));

        StringBuilder sb = new StringBuilder();
        sb.append("""
                You translate questions and instructions about a linguistic fieldwork archive into SPARQL for an RDF4J triplestore. \
                The archive is described mainly with RiC-O (Records in Contexts). You work inside FieldArchive, a desktop application: \
                the user sees your query and your explanation, and nothing runs until they confirm.

                # What to answer
                Your final answer is a JSON object following the given schema:
                - queryType SELECT for questions (reading), UPDATE for changes (create, modify, delete), CLARIFY when the user must decide something first.
                - sparql: the complete query with its PREFIX declarations (empty for CLARIFY).
                - explanation: one to three sentences for the user, in the language of the question: what the query does, and what it is based on. For CLARIFY, the question to ask.
                - newEntities, targetEntityIri, clarifyKind, candidateIris, encodingOptions: as described in the schema; empty when they don't apply.

                # Rules for queries
                - SELECT reads all graphs (the default graph is the union of all graphs). Add LIMIT 200 unless the user asks for everything or a count. \
                Return readable columns: for entities, also their name with OPTIONAL.
                - In an UPDATE, the INSERT and DELETE templates must use GRAPH <internal graph> { … } explicitly. Never use WITH (it would also hide the \
                external graphs from the WHERE clause). The WHERE clause may read any graph.
                - Use DELETE { … } INSERT { … } WHERE { … } for edits, INSERT DATA for pure creations.
                - To delete an entity, remove its triples and the triples that point to it, in the internal graph only (as the application's own delete does).
                - Never write dcterms:created or dcterms:modified: the application maintains them.
                - New entities: never invent IRIs. Use the placeholders <urn:fieldarchive:new:1>, <urn:fieldarchive:new:2>, … and list each one in newEntities \
                with its class and name; the application replaces them with real IRIs. Give every new entity an rdf:type and a rico:name.
                - Existing entities: use only IRIs you have seen in a tool result or a history example. For an UPDATE, find the entity with search_entities \
                and write its IRI; never select it with a FILTER on its name.
                - Do not put comments (#) in the query.

                # Names
                An entity's name is its rico:name literal (the application sets one on every entity it creates), not a rico:hasOrHadName → rico:Name node. \
                To read a name, use the first present of: """).append(String.join(", ", labelOrder)).append("""
                . This rule is fixed: it is not subject to the precedence rule below.

                # How to use the tools
                When the request refers to specific existing entities by name or description, look them up with search_entities before writing any query, \
                and write UPDATEs against the IRIs you found. Use describe_entity to see which properties an entity actually uses and where each value comes \
                from. If no candidate, or several plausible candidates, are found, answer CLARIFY with clarifyKind ENTITY and the candidate IRIs (none if \
                nothing was found, explaining it). A value whose source is external is read-only: to change it, add an internal value and say so in the \
                explanation.

                # How to choose an encoding
                To record a piece of information (a gender, a language, a role, a date…), decide which property and which kind of value to use in this order:
                1. a history example marked "corrected by the user" or "decided by the user";
                2. how this project's data already records it: the usage profile, or find_usage. If existing values fit, reuse them: link to the existing \
                vocabulary entity, or use the existing literal form, matching across languages ('masculine' = 'masculin' = 'male'), rather than adding a new variant;
                3. the main properties and main terminologies of the configuration;
                4. the RiC-O definitions (search_ontology when needed), for entities that have a RiC-O type.
                Only RiC-O is described to you. Properties of other ontologies may be used only as the project data already uses them: never introduce a \
                property from another ontology that the usage profile or find_usage does not show. Do not add a type from another ontology to an entity just \
                to be able to use one of its properties.
                If after these steps two or more encodings remain plausible, or none is found, answer CLARIFY with clarifyKind ENCODING, giving each option \
                with its basis ("project data", "configuration" or "RiC-O"), an example triple, and a one-sentence statement of the decision.
                If the chosen encoding points to a vocabulary entity that does not exist yet (for example no rico:DemographicGroup named 'masculine'), \
                create it in the same UPDATE with a placeholder IRI, its type and a rico:name, and say so in the explanation.

                # History examples
                Examples from this project's history show a question and the query that was actually run for it. Reuse their encodings: which class, \
                property and graph pattern express which wording. Do not reuse their IRIs unless the same entity is clearly meant, and even then confirm it \
                with search_entities. An example marked "corrected by the user" or "decided by the user" overrides any conflicting example or schema guess. \
                When two examples disagree, prefer the newer one.

                # Decisions already made
                Statements under "Decided by the user" in the message are the user's answers to your earlier questions: follow them and do not ask again.
                """);
        if (full) {
            sb.append("\n# RiC-O\n").append(ricoDigest.text());
        } else {
            sb.append("\n# RiC-O\nThe RiC-O ontology is not listed here: use search_ontology to find its classes and properties.\n");
        }
        return sb.toString();
    }

    // =========================
    //  Project part
    // =========================

    private String projectPart(UsageProfileService.Profile profile, boolean full) {
        String projectName = ProjectContext.getProjectName();
        StringBuilder sb = new StringBuilder("# This project\n");
        sb.append("Project: ").append(projectName).append('\n');

        String internal = dsService.getGraphIri(projectName + "_internal").stringValue();
        sb.append("\n## Graphs\n");
        sb.append("- internal graph (the only one you may write to): <").append(internal).append(">\n");
        for (DataSourceDto ds : dsService.listDataSources()) {
            if (ds.graphIri == null || ds.graphIri.equals(internal)) continue;
            sb.append("- external data source \"").append(ds.shortName).append("\" (read-only): <").append(ds.graphIri).append(">\n");
        }
        sb.append("- never touch: <").append(RdfContexts.CTX_META).append(">, <")
                .append(projectService.getMetadataContext(projectName, SimpleValueFactory.getInstance()).stringValue())
                .append("> (application metadata) and <").append(RdfContexts.CTX_ONTO_RICO).append("> (the RiC-O ontology itself)\n");

        sb.append("\n## Prefixes\n").append(Prefixes.declarations(profile.prefixes));

        sb.append("\n## Configuration\n").append(configuration(profile.prefixes));

        sb.append("\n## Usage profile (how this project's data records things; counts are numbers of entities)\n");
        sb.append(usageProfile.render(profile, full ? 30 : 10));

        if (full && sampleEntities > 0) {
            String samples = samples(profile);
            if (!samples.isEmpty()) sb.append("\n## Sample entities (the fullest entity of some main types)\n").append(samples);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String configuration(Map<String, String> prefixes) {
        StringBuilder sb = new StringBuilder();
        ontologyService.getConfiguredOntologies().forEach((ns, data) -> {
            String namespace = (ns.endsWith("#") || ns.endsWith("/")) ? ns : ns + "#";
            boolean rico = namespace.equals(RdfNamespaces.RICO);
            List<String> mainTypes = configuredList(data.get("mainTypes"), namespace, rico, prefixes);
            List<String> terminologies = configuredList(data.get("mainTerminologies"), namespace, rico, prefixes);
            List<String> props = new ArrayList<>();
            if (data.get("mainProperties") instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    List<String> ps = configuredList(e.getValue(), namespace, rico, prefixes);
                    if (!ps.isEmpty()) props.add(e.getKey() + ": " + String.join(", ", ps));
                }
            }
            if (mainTypes.isEmpty() && terminologies.isEmpty() && props.isEmpty()) return;
            sb.append(data.getOrDefault("name", ns)).append(":\n");
            if (!mainTypes.isEmpty()) sb.append("  main types: ").append(String.join(", ", mainTypes)).append('\n');
            if (!terminologies.isEmpty()) sb.append("  main terminologies (controlled vocabularies of reusable entities): ")
                    .append(String.join(", ", terminologies)).append('\n');
            if (!props.isEmpty()) sb.append("  main properties by type: ").append(String.join("; ", props)).append('\n');
        });
        return sb.isEmpty() ? "(none)\n" : sb.toString();
    }

    /** Values of a configuration list, expanded with the ontology's namespace; RiC-O entries that don't exist are left out. */
    private List<String> configuredList(Object section, String namespace, boolean rico, Map<String, String> prefixes) {
        List<?> raw = section instanceof List<?> l ? l
                : section instanceof Map<?, ?> m && m.get("value") instanceof List<?> l2 ? l2 : List.of();
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            String s = String.valueOf(o);
            String iri = s.contains("://") ? s : namespace + s;
            if (rico && iri.startsWith(RdfNamespaces.RICO) && ricoDigest.entry(iri) == null) {
                System.err.println("WARNING: configuration.json entry " + s + " is not a RiC-O term; left out of the agent's prompt");
                continue;
            }
            out.add(Prefixes.curie(iri, prefixes));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String samples(UsageProfileService.Profile profile) {
        Set<String> mainTypes = new LinkedHashSet<>();
        ontologyService.getConfiguredOntologies().forEach((ns, data) -> {
            String namespace = (ns.endsWith("#") || ns.endsWith("/")) ? ns : ns + "#";
            if (data.get("mainTypes") instanceof Map<?, ?> m && m.get("value") instanceof List<?> l) {
                for (Object o : l) mainTypes.add(String.valueOf(o).contains("://") ? String.valueOf(o) : namespace + o);
            }
        });
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
            for (UsageProfileService.TypeUsage t : profile.types) {
                if (shown >= 5) break;
                if (!mainTypes.contains(t.type)) continue;
                String fullest = null;
                TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                        "SELECT ?s (COUNT(*) AS ?n) WHERE { ?s a <" + t.type + "> . ?s ?p ?o } GROUP BY ?s ORDER BY DESC(?n) LIMIT 1");
                q.setMaxExecutionTime(10);
                try (TupleQueryResult r = q.evaluate()) {
                    if (r.hasNext()) {
                        BindingSet bs = r.next();
                        fullest = bs.getValue("s").stringValue();
                    }
                }
                if (fullest == null) continue;
                RdfEntityDto e = rdfEntityService.getByIri(SimpleValueFactory.getInstance().createIRI(fullest));
                sb.append(Prefixes.curie(t.type, profile.prefixes)).append(" example <").append(e.iri).append(">:\n");
                int lines = 0;
                for (RdfPropertyDto p : e.properties) {
                    for (RdfValueDto v : p.values) {
                        if (lines++ >= 25) break;
                        String value = "iri".equals(v.kind) ? "<" + v.value + ">" + (v.name != null && !v.name.isBlank() ? " (\"" + v.name + "\")" : "")
                                : "\"" + (v.value.length() > 120 ? v.value.substring(0, 120) + "…" : v.value) + "\"";
                        sb.append("  ").append(Prefixes.curie(p.predicate, profile.prefixes)).append(' ').append(value).append('\n');
                    }
                }
                shown++;
            }
        } catch (RuntimeException e) {
            return "";
        }
        return sb.toString();
    }

    // =========================
    //  User message
    // =========================

    private String userPart(String question, List<NlClarification> clarifications, List<NlHistoryEntry> examples) {
        StringBuilder sb = new StringBuilder();
        if (!examples.isEmpty()) {
            sb.append("# Examples from this project's history\n").append(history.render(examples));
        }
        if (clarifications != null && !clarifications.isEmpty()) {
            sb.append("# Decided by the user\n");
            for (NlClarification c : clarifications) {
                sb.append("- ").append(c.statement != null && !c.statement.isBlank() ? c.statement
                        : "the entity meant is <" + c.entityIri + ">").append('\n');
            }
            sb.append('\n');
        }
        sb.append("# Question\n").append(question.trim()).append('\n');
        return sb.toString();
    }
}
