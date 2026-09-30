package fr.cnrs.lacito.fieldarchive.services.nlquery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.dtos.*;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.services.DataSourceService;
import fr.cnrs.lacito.fieldarchive.services.RdfEntityService;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.*;
import fr.cnrs.lacito.fieldarchive.services.nlquery.llm.LlmProvider.*;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates a natural-language question into SPARQL with the agent chosen by the user.
 * Everything here is provider-neutral: the prompt, the tool loop, the validation, the IRI
 * minting. Nothing is executed: the query runs through the /sparql endpoints once the user
 * confirms it.
 */
@Service
public class NlQueryService {

    private static final Logger log = LoggerFactory.getLogger(NlQueryService.class);
    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final List<String> AGENT_ORDER = List.of("claude", "openai", "local", "builtin");

    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final NlPromptBuilder promptBuilder;
    private final NlQueryTools tools;
    private final RdfEntityService rdfEntityService;
    private final DataSourceService dsService;
    private final ApiKeyStore keys;
    private final int maxTurns;

    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();

    public NlQueryService(List<Agent> agentBeans, NlPromptBuilder promptBuilder, NlQueryTools tools,
                          RdfEntityService rdfEntityService, DataSourceService dsService, ApiKeyStore keys,
                          @Value("${fieldarchive.nlquery.max-turns:8}") int maxTurns) {
        Map<String, Agent> byId = new HashMap<>();
        for (Agent a : agentBeans) byId.put(a.id(), a);
        for (String id : AGENT_ORDER) if (byId.containsKey(id)) agents.put(id, byId.get(id));
        this.promptBuilder = promptBuilder;
        this.tools = tools;
        this.rdfEntityService = rdfEntityService;
        this.dsService = dsService;
        this.keys = keys;
        this.maxTurns = maxTurns;
    }

    // =========================
    //  Status and keys
    // =========================

    public NlQueryStatusDto status() {
        NlQueryStatusDto out = new NlQueryStatusDto();
        for (Agent a : agents.values()) out.providers.add(a.status());
        return out;
    }

    public void saveKey(SaveApiKeyRequest req) {
        if (req == null || req.apiKey == null || req.apiKey.isBlank()) throw new BadRequestException("The API key is empty.");
        if (!(agents.get(req.provider) instanceof LlmProvider p) || !List.of("claude", "openai").contains(req.provider)) {
            throw new BadRequestException("No API key is used by agent: " + req.provider);
        }
        p.validateKey(req.apiKey.trim());   // throws a readable error when refused; nothing is written then
        keys.save(req.provider, req.apiKey.trim());
    }

    public void deleteKey(String provider) {
        if (!List.of("claude", "openai").contains(provider)) throw new BadRequestException("No API key is used by agent: " + provider);
        keys.delete(provider);
    }

    public void cancel(String requestId) {
        if (requestId != null) cancelled.put(requestId, true);
    }

    // =========================
    //  Translation
    // =========================

    /** Everything a translation must re-check before each step: cancel, deadline, same project. */
    private final class Guard {
        final String requestId;
        final long deadline;
        final Repository repo;
        final String agentName;

        Guard(String requestId, int timeoutSeconds, String agentName) {
            this.requestId = requestId;
            this.deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            this.repo = ProjectContext.getRepository();
            this.agentName = agentName;
        }

        void check() {
            if (requestId != null && cancelled.containsKey(requestId)) throw new BadRequestException("Cancelled.");
            if (System.currentTimeMillis() > deadline) throw new BadRequestException(agentName + ": timed out.");
            if (ProjectContext.getRepository() != repo) {
                throw new BadRequestException("The project changed during the translation: nothing was done.");
            }
        }
    }

    public NlQueryDto translate(NlQueryRequest req) {
        if (!ProjectContext.isOpen()) throw new BadRequestException("Aucun projet ouvert.");
        if (req == null || req.question == null || req.question.isBlank()) throw new BadRequestException("The question is empty.");
        Agent agent = agents.get(req.provider);
        if (agent == null) throw new BadRequestException("Unknown agent: " + req.provider);
        ProviderStatusDto st = agent.status();
        if (!st.available) throw new BadRequestException(agent.label() + " is not available: " + st.reason);
        List<NlClarification> clarifications = req.clarifications != null ? req.clarifications : List.of();

        try {
            IRI internalGraph = dsService.getGraphIri(ProjectContext.getProjectName() + "_internal");
            if (agent instanceof DirectAgent direct) {
                Guard guard = new Guard(req.requestId, 60, agent.label());
                DirectAgent.DirectAnswer answer = direct.translate(req.question, clarifications);
                guard.check();
                NlQueryDto dto = new NlQueryDto();
                dto.lookups.addAll(answer.lookups());
                dto.examples.addAll(answer.examples());
                if ("UNSUPPORTED".equals(answer.output().queryType)) {
                    dto.queryType = "UNSUPPORTED";
                    dto.explanation = answer.output().explanation;
                    return withAgent(dto, agent);
                }
                SparqlGuard.Result checked = validate(answer.output(), internalGraph); // no retry: a failure is a bug in a pattern
                return withAgent(finish(answer.output(), checked, dto), agent);
            }
            LlmProvider provider = (LlmProvider) agent;
            return withAgent(runLoop(provider, req, clarifications, internalGraph), agent);
        } finally {
            if (req.requestId != null) cancelled.remove(req.requestId);
        }
    }

    private static NlQueryDto withAgent(NlQueryDto dto, Agent agent) {
        dto.provider = agent.id();
        dto.model = agent.model();
        return dto;
    }

    private NlQueryDto runLoop(LlmProvider provider, NlQueryRequest req, List<NlClarification> clarifications, IRI internalGraph) {
        Guard guard = new Guard(req.requestId, provider.totalTimeoutSeconds(), provider.label());
        boolean compact = provider.contextBudget() == ContextBudget.COMPACT;
        int toolChars = compact ? 1000 : 4000;

        NlPromptBuilder.Prompt prompt = promptBuilder.build(req.question, clarifications, provider.contextBudget());
        log.debug("NL query [{}] prompt: static={} chars, project={} chars, {} history examples",
                provider.id(), prompt.staticSystem().length(), prompt.projectSystem().length(), prompt.examples().size());
        log.debug("NL query user message:\n{}", prompt.userMessage());
        log.trace("NL query project part:\n{}", prompt.projectSystem());

        AgentSession session = provider.start(new AgentRequest(prompt.staticSystem(), prompt.projectSystem(), prompt.userMessage(),
                tools.specs(), tools.outputSchema(), ProjectContext.getProjectName()));

        NlQueryDto dto = new NlQueryDto();
        int validationFailures = 0;
        String lastSparql = null;
        for (int turn = 0; turn < maxTurns; turn++) {
            guard.check();
            AgentTurn t = session.next();
            log.debug("NL query [{}] turn {}: {} {}", provider.id(), turn + 1, t.kind(), t.usage());
            switch (t.kind()) {
                case REFUSED -> throw new BadRequestException(provider.label() + " declined this request.");
                case TRUNCATED -> throw new BadRequestException(provider.label() + " ran out of output space before finishing. Try a simpler question.");
                case TOOL_CALLS -> {
                    List<ToolResult> results = new ArrayList<>();
                    for (ToolCall call : t.toolCalls()) {
                        guard.check();
                        NlQueryTools.Outcome o = tools.run(call.name(), call.argumentsJson(), toolChars, prompt.prefixes());
                        dto.lookups.add(o.trace());
                        results.add(new ToolResult(call.id(), o.content(), o.isError()));
                    }
                    session.addToolResults(results);
                }
                case FINAL -> {
                    ModelOutput output;
                    SparqlGuard.Result checked;
                    try {
                        output = parseOutput(t.finalJson());
                        lastSparql = output.sparql;
                        checked = validate(output, internalGraph);
                    } catch (SparqlGuard.GuardException e) {
                        validationFailures++;
                        log.debug("NL query [{}] answer rejected: {}", provider.id(), e.getMessage());
                        if (validationFailures >= 2) {
                            throw new BadRequestException(provider.label() + " could not produce a valid query: " + e.getMessage()
                                    + (lastSparql != null && !lastSparql.isBlank() ? "\nLast query:\n" + lastSparql : ""));
                        }
                        session.addUserText("Your answer was rejected: " + e.getMessage() + "\nFix it and give your final answer again.");
                        continue;
                    }
                    return finish(output, checked, dto);
                }
            }
        }
        throw new BadRequestException(provider.label() + " did not finish within " + maxTurns + " steps. Try a more specific question.");
    }

    private ModelOutput parseOutput(String json) {
        JsonNode node;
        try {
            String s = json == null ? "" : json.trim();
            // tolerate a fenced block from a local model
            if (s.startsWith("```")) s = s.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            node = JSON.readTree(s);
        } catch (Exception e) {
            throw new SparqlGuard.GuardException("The final answer is not valid JSON: " + e.getMessage());
        }
        List<String> problems = SchemaValidator.validate(node, tools.outputSchema());
        if (!problems.isEmpty()) throw new SparqlGuard.GuardException("The final answer does not follow the schema: " + String.join("; ", problems));
        return JSON.convertValue(node, ModelOutput.class);
    }

    private boolean exists(String iri) {
        try {
            return rdfEntityService.exists(SimpleValueFactory.getInstance().createIRI(iri));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Checks an answer before it is shown; throws a GuardException the model can act upon. */
    private SparqlGuard.Result validate(ModelOutput o, IRI internalGraph) {
        if ("CLARIFY".equals(o.queryType)) {
            if ("ENTITY".equals(o.clarifyKind)) {
                for (String iri : o.candidateIris) {
                    if (!exists(iri)) throw new SparqlGuard.GuardException("Candidate <" + iri + "> does not exist: give only IRIs found with search_entities.");
                }
            } else if ("ENCODING".equals(o.clarifyKind)) {
                if (o.encodingOptions.isEmpty()) throw new SparqlGuard.GuardException("An ENCODING clarify needs at least one option.");
            } else {
                throw new SparqlGuard.GuardException("A CLARIFY answer needs clarifyKind ENTITY or ENCODING.");
            }
            return new SparqlGuard.Result();
        }
        if (o.sparql == null || o.sparql.isBlank()) throw new SparqlGuard.GuardException("The query is empty.");
        SparqlGuard.Result r = SparqlGuard.check(o.sparql, o.queryType, internalGraph, this::exists);

        Set<String> declared = new LinkedHashSet<>();
        for (ModelOutput.NewEntity ne : o.newEntities) {
            if (ne.typeIri == null || ne.typeIri.isBlank()) throw new SparqlGuard.GuardException("newEntities: " + ne.placeholder + " has no typeIri.");
            declared.add(ne.placeholder);
        }
        for (String used : r.placeholdersUsed) {
            if (!declared.contains(used)) throw new SparqlGuard.GuardException("<" + used + "> is used in the query but not listed in newEntities.");
        }
        for (String d : declared) {
            if (!r.placeholdersUsed.contains(d)) throw new SparqlGuard.GuardException(d + " is listed in newEntities but not used in the query.");
        }
        return r;
    }

    /** Mints the IRIs of new entities and builds the answer. */
    private NlQueryDto finish(ModelOutput o, SparqlGuard.Result checked, NlQueryDto dto) {
        dto.queryType = o.queryType;
        dto.explanation = o.explanation;

        if ("CLARIFY".equals(o.queryType)) {
            dto.clarifyKind = o.clarifyKind;
            for (String iri : o.candidateIris) {
                EntityMatchDto m = rdfEntityService.matchOf(SimpleValueFactory.getInstance().createIRI(iri));
                if (m != null) dto.candidates.add(m);
            }
            for (ModelOutput.EncodingOption opt : o.encodingOptions) {
                EncodingOptionDto e = new EncodingOptionDto();
                e.id = opt.id;
                e.label = opt.label;
                e.description = opt.description;
                e.basis = opt.basis;
                e.exampleTriple = opt.exampleTriple;
                e.statement = opt.statement;
                dto.encodingOptions.add(e);
            }
            return dto;
        }

        String sparql = o.sparql;
        Map<String, String> minted = new LinkedHashMap<>();
        for (ModelOutput.NewEntity ne : o.newEntities) {
            String iri = rdfEntityService.mintIri(ne.typeIri).stringValue();
            minted.put(ne.placeholder, iri);
            sparql = sparql.replace("<" + ne.placeholder + ">", "<" + iri + ">");
            dto.createdEntityIris.add(iri);
        }
        dto.sparql = sparql;

        String target = o.targetEntityIri == null ? "" : o.targetEntityIri.trim();
        if (minted.containsKey(target)) target = minted.get(target);
        if ("UPDATE".equals(o.queryType)) {
            boolean written = checked.writtenSubjects.contains(o.targetEntityIri) || minted.containsValue(target);
            if (!written) {
                // the target must be something the update actually writes
                target = !dto.createdEntityIris.isEmpty() ? dto.createdEntityIris.get(0)
                        : checked.writtenSubjects.stream().filter(s -> !s.startsWith(NlQueryHistoryService.PLACEHOLDER_PREFIX)).findFirst().orElse("");
            }
        } else if (!target.isEmpty() && !exists(target)) {
            target = "";
        }
        dto.targetEntityIri = target.isEmpty() ? null : target;
        return dto;
    }
}
