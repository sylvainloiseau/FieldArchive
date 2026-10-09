# Plan: Natural-language query dialog ("Ask") backed by an AI agent (Claude, ChatGPT, a local model, or a built-in offline agent)

## Context

Users who don't write SPARQL have no way to ask questions of a project or make bulk edits. The goal is a modal, opened from the shared header, where the user types a question or instruction in natural language. An AI agent turns it into SPARQL, shows the query, and runs it only when the user clicks a button. What happens next depends on the query:

- **SELECT**: navigate to `/sparql`, fill the editor with the generated query and show its results in the existing results area.
- **UPDATE** (create, modify or delete): run it, then open the `entity-details` dialog on the entity that was created or edited, if one can be identified.

Current state that the plan builds on:

- The shared header is `components/nav-bar` (`NavBarComponent`). Its `navItems` are route buttons, greyed out when `activeProject$` has no name.
- `/sparql` is `SparqlPageComponent`, a wrapper around `SparqlComponent` (`components/sparql`). `SparqlComponent` has `query`, `selectedType` and `runQuery()`. Its UPDATE branch is **not implemented**: `runQuery()` only handles `SELECT`, and `GestionRessourcesService.runUpdateQuery` is commented out.
- Backend `POST /sparql/select` and `POST /sparql/update` exist (`SparqlService`). They classify queries by naive keyword matching, so a SELECT whose literal contains "delete" is rejected.
- `EntityDetailsComponent` is opened only by `GestionRessourcesComponent.openEntityDetailsDialog()`, with `data: { ontologyLabels, selectedEntityId }`. `ontologyLabels` is the `GET /ontology/types` payload (`GestionRessourcesService.getTypes()`).
- New entity IRIs follow `<project prefix>/<typeLocalName>/<uuid>` (`RdfEntityService.iriFromKey`). User-editable triples must go into `urn:datasource:<projectName>_internal`.
- No LLM integration exists yet.

## Decisions (defaults — change before implementing if needed)

1. **Four agents, chosen in a drop-down at the top of the dialog: Claude and ChatGPT (paid, described here), a local model and a built-in agent (free, decision 8).** Claude and ChatGPT are called from the backend with their official Java SDKs: `com.anthropic:anthropic-java` (Messages API) and `com.openai:openai-java` (Responses API, which tool calling on current OpenAI models requires). API keys never reach the Angular app. Default models, each configurable in `application.properties`: `claude-opus-5-5`, and `gpt-6-astra` (OpenAI's current flagship; `gpt-6-sol` is a much cheaper alternative). **Everything except the HTTP call is shared**: the prompt, the tools, the loop, validation, IRI minting, the history. Only a thin provider layer (section 1.7) differs, so a feature added once works with every LLM agent (Claude, ChatGPT, local). Note for users: a ChatGPT Plus/Pro subscription does **not** include API access. The "ChatGPT" option needs an OpenAI API key from platform.openai.com, billed separately.
2. **A bounded agent with read-only lookup tools.** The system prompt tells the model the *vocabulary* (section 1.3). The model can't know the *data*, though: which entity "Marie Dupond" is, how her name is actually spelled, whether there are two of her, or whether her name exists only in an external source. So before writing its answer, the model can call read-only tools: `search_entities`, `describe_entity`, `run_select`, `search_history`, `search_ontology` and `find_usage` (section 1.3). An UPDATE then targets concrete IRIs instead of guessing a `FILTER` on a label. It also gives a reliable `targetEntityIri` for opening the entity dialog afterwards. When the lookup is inconclusive (no match, or several plausible matches), the model returns a `CLARIFY` answer instead of a query: which entity, or which encoding (decision 9). The loop is written by hand, not with an SDK's tool runner. One loop drives all three LLM agents; the Anthropic runner builds tool objects by deserializing JSON, so they can't receive the Spring services they need. A manual loop also lets the SPARQL validation errors go back to the model as ordinary turns. The loop is capped at 8 model turns.
3. **Execution always needs a user click.** The generated query is shown read-only (it can be copied), with the explanation. UPDATE queries get a warning style.
4. **Writes are confined to the internal graph.** The prompt requires `GRAPH <internal>` / `WITH <internal>`, and the backend rejects any generated UPDATE that writes to a different graph (checked on the parsed algebra, not by string matching).
5. **New IRIs are minted by the backend, not invented by the model.** The model writes placeholders `<urn:fieldarchive:new:1>`, `<urn:fieldarchive:new:2>`, … and declares each one's type in `newEntities`. The backend replaces them with `<prefix>/<typeLocalName>/<uuid>`, the same scheme as `RdfEntityService`.
6. **The project keeps a history of questions and the queries actually run from them**, in `$FIELD_ARCHIVE_DATA/projects/<projectName>/natural_language_query_history.json`, next to `store/`. The agent receives the most relevant past entries as worked examples, so it reuses the RDF encodings this project has already settled on. For example: "born in" → `rico:hasBirthDate`, "recorded by" → the property actually used here. A query the user corrected in the SPARQL editor before running it is recorded as a correction, and takes precedence over other examples. See section 1.6. The history is shared by all agents: a correction made while using one agent also guides the others, including the Built-in one (section 1.10).
7. **API keys can be set from the dialog, not only as environment variables.** A packaged app started from the Dock, Finder or the Start menu does not inherit the shell's environment, so `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` exported in `.zshrc` only works when the app is started from a terminal. The backend therefore also reads keys from `$FIELD_ARCHIVE_DATA/settings/llm-keys.json`, written through the dialog (section 1.8). An environment variable, when set, takes precedence.
8. **Two agents that need no payment and no account**, next to Claude and ChatGPT in the drop-down:
   - **Local model (Ollama)**: a real LLM agent running on the user's computer. It is free, needs no key, and no project data leaves the machine. It requires installing Ollama and downloading a model of about 5–15 GB, and it is slower and less reliable than the paid agents. It runs **the same agent loop and tools** as Claude and ChatGPT (section 1.9).
   - **Built-in (no AI)**: a deterministic translator for a documented set of question shapes ("list all persons", "rename X to Y", "create a person named X"…), in French and English. Nothing to install and always available, but it only understands the phrasings it knows (section 1.10). It uses the same entity lookup, validation and history, and it gets better as the history grows.
   
   The user picks according to machine and needs: Built-in works everywhere, the local model covers free-form questions on a capable machine, and the paid agents give the best results.
9. **What the agent knows about vocabulary, and in which order it trusts it.** Two sources, deliberately asymmetric:
   - **RiC-O is the only ontology the agent reads.** A condensed digest of `rico.rdf` (labels, domain → range, inverses, superclasses, definitions and scope notes; about 25–40K tokens instead of the ~400K-token raw file) is part of the cached prompt for Claude and ChatGPT. It is searchable with a `search_ontology` tool by every LLM agent (section 1.11). The definitions are what link a word like "gender" to `rico:DemographicGroup`.
   - **All other ontologies (FOAF, SKOS, PNV, BIO, BIBO, BIBFRAME, and any *UsedOnlyOntology*) are never mined.** Their files are not read for the agent. Their properties reach the agent only through **how the project data uses them**, via a usage profile computed with SPARQL (per type: properties used, counts, value kinds, recurring values), in the prompt and through a `find_usage` tool (section 1.12).
   
   **Precedence rule**, stated in the prompt:
   1. a history entry corrected or decided by the user;
   2. how the project already encodes it (usage profile, `find_usage`), reusing existing values and vocabulary entities;
   3. `mainProperties` / `mainTerminologies` in `configuration.json`;
   4. the RiC-O digest, for entities with a RiC-O type.
   
   If the choice is still open, the agent asks, with a `CLARIFY` about the **encoding** (section 1.3).
10. **The backend maintains the app's bookkeeping triples, not the model.** Entities created through the UI get `dcterms:created`, edits set `dcterms:modified`, and every write refreshes the internal data source's `dcterms:modified` in `CTX_META` (`RdfEntityService.create` / `updateByKey` / `touchInternalDataSource`). An UPDATE generated from a question must leave the data in the same state as the equivalent UI action. The model is told **never** to write these triples. After every successful UPDATE, the backend adds them in the same transaction, from the statements the UPDATE actually added or removed in the internal graph (section 1.5). Deleting follows `RdfEntityService.deleteByKey`: only the entity's own triples in the internal graph are removed, and links pointing to it from other entities are left as they are, exactly like a delete in the UI.

## 1. Backend

### 1.1 Dependencies and configuration
- `Backend/pom.xml`: add `com.anthropic:anthropic-java` (2.x) and `com.openai:openai-java` (4.x).
  - Both SDKs are built on Jackson 2 (`com.fasterxml.jackson`), OkHttp and the Kotlin stdlib. Spring Boot 4 uses Jackson 3 (`tools.jackson`), so there is no package clash, but the two SDKs must agree on one resolved version of Jackson 2, OkHttp and Kotlin.
  - Check with `mvn dependency:tree`, pin in `<dependencyManagement>` if Maven picks an older version, and smoke-test one real call per provider after any version bump.
- `application.properties`:
  ```
  fieldarchive.nlquery.claude.model=claude-opus-5-5
  fieldarchive.nlquery.openai.model=gpt-6-astra
  fieldarchive.nlquery.max-output-tokens=16000
  fieldarchive.nlquery.max-turns=8
  fieldarchive.nlquery.local.base-url=http://localhost:11434/v1
  fieldarchive.nlquery.local.model=gpt-oss:20b
  fieldarchive.nlquery.local.timeout-seconds=300
  ```
  No new dependency for the local agent: it reuses `openai-java` with a different base URL (section 1.9). The built-in agent uses only RDF4J and the JDK.
- Add `com.networknt:json-schema-validator`. `NlQueryService` validates **every** model output and every tool call's arguments against `output-schema.json` / `tools.json`, whichever provider produced them. For Claude and ChatGPT this is a safety net behind `strict`; for the local agent, which has no `strict`, it's the only guarantee. A failure is sent back to the model like a SPARQL validation error.
- Additional properties: `fieldarchive.nlquery.total-timeout-seconds=180`, `fieldarchive.nlquery.local.total-timeout-seconds=900`, `fieldarchive.nlquery.sample-entities=1`, `fieldarchive.nlquery.rico-scope-notes=true`.
- No client beans at startup. Each provider builds its SDK client **lazily** from the key it resolves (section 1.8), and rebuilds it when the key changes. The app must still start, and every other feature must still work, when no key is set.

### 1.2 DTOs (`dtos/`)
- `NlQueryRequest { String requestId; String provider; String question; List<NlClarification> clarifications; }`
  - `requestId` is a UUID generated by the dialog for each translate call, used to cancel it (section 1.3).
  - `provider` is `"claude"`, `"openai"`, `"local"` or `"builtin"`; an unknown or unavailable provider → `BadRequestException`.
  - `clarifications` holds the answers the user gave to earlier `CLARIFY` rounds for this question, possibly several (first "which Jean Dupond?", then "how should gender be recorded?"). They are sent back with the original question. Each request stays self-contained, so no conversation state is kept on the server, and the user can even switch agent between rounds.
- `NlClarification { String kind; String entityIri; String optionId; String statement; }`
  - `kind` is `ENTITY` or `ENCODING`;
  - `statement` is the human-readable decision passed to the model, e.g. "Jean Dupond is `<iri>`", or "Record gender with `rico:hasOrHadDemographicGroup` → a `rico:DemographicGroup` entity".
- `NlQueryDto` (response, public fields to match most DTOs): `queryType` (`SELECT` | `UPDATE` | `CLARIFY` | `UNSUPPORTED`; the last is only produced by the Built-in agent when no shape matches, with `examples` filled), `sparql` (placeholders already replaced; null for `CLARIFY`), `explanation` (for `CLARIFY`, the question to ask the user), `targetEntityIri` (nullable: the main entity created or edited), `createdEntityIris` (list), `clarifyKind` (`ENTITY` | `ENCODING`, for `CLARIFY`), `candidates` (for an `ENTITY` clarify: list of `{iri, label, types, source}`), `encodingOptions` (for an `ENCODING` clarify: list of `EncodingOptionDto`), `lookups` (short trace of the tool calls made, e.g. `search_entities("marie dupond") → 1 match`, shown in the dialog so the user can see what the query is based on), `provider` and `model` (shown under the result and recorded in the history), `examples` (list of phrasings the Built-in agent understands, for `UNSUPPORTED` only).
- `NlQueryModelOutput`: the model's **final** answer. Every field is present, and fields that don't apply are `null` or empty (the strict-schema rule of section 1.7):
  - `queryType`: `SELECT`, `UPDATE` or `CLARIFY`;
  - `sparql`: with `<urn:fieldarchive:new:N>` placeholders for new entities;
  - `explanation`: a short text in the language of the question;
  - `newEntities`: list of `{ placeholder, typeIri, name }`, one per placeholder used;
  - `targetEntityIri`: an existing IRI, or a placeholder;
  - `clarifyKind`: `ENTITY` or `ENCODING`;
  - `candidateIris`: for `ENTITY`. The model returns only IRIs. The backend fills `NlQueryDto.candidates` (label, types, source) from the store, so the dialog never shows a label the model made up, and every candidate IRI must exist;
  - `encodingOptions`: for `ENCODING`, list of `{ id, label, description, basis, exampleTriple, statement }`.
  
  It is the record the model's final answer is parsed into, with Jackson, by `NlQueryService`, whichever provider produced it. Its JSON Schema is **not** derived from the class by one SDK's helper. It is a hand-written file, `resources/nlquery/output-schema.json`, given as-is to every LLM provider, so all are held to exactly the same contract (constraints in section 1.7).
- `EntityMatchDto { iri, label, types, source, datasourceShortName }`: returned by `search_entities`.
- `EncodingOptionDto { id, label, description, basis, exampleTriple, statement }`. One way of recording the requested information, proposed by the agent:
  - `basis` says where the option comes from: `"project data"`, `"configuration"` or `"RiC-O"`;
  - `exampleTriple` shows it on the entity, e.g. `<Jean Dupond> rico:hasOrHadDemographicGroup <masculine>`;
  - `statement` is what goes back into `NlClarification` when the option is picked.
- `NlQueryStatusDto { List<ProviderStatusDto> providers; }`. `ProviderStatusDto { id, label, model, available, free, keySource, reason }`:
  - `label` is "Claude (Anthropic)", "ChatGPT (OpenAI)", "Local model (Ollama)" or "Built-in (no AI)";
  - `free` is `true` for local and built-in, shown as a "free" badge in the drop-down;
  - `keySource` is `"environment"`, `"settings"` or `null`;
  - `reason` says why a provider is unavailable, e.g. "No OpenAI API key".
  
  The key itself is never returned.
- `SaveApiKeyRequest { String provider; String apiKey; }`

### 1.3 `services/NlQueryService`
- `status()`: one entry per registered `Agent` (the three `LlmProvider`s and the `BuiltinAgent`, section 1.7). It needs no open project. Built-in is always available, so the dialog always has at least one agent.
- `translate(NlQueryRequest request)`: provider-neutral. It picks the `Agent` by id and never touches an SDK type itself.
  1. `requireProjectOpen()`, and **capture the project name**. `ProjectContext` is process-global and a translation can take minutes (local model), so each tool call, and the final result, first checks that the same project is still open. If another project was opened in between, the translation stops with "The project changed during the translation".
     
     **Deadline and cancel**: the whole translation has a deadline, `fieldarchive.nlquery.total-timeout-seconds` (default 180; 900 for the local agent). The dialog's Cancel button calls `POST /nlquery/cancel/{requestId}`, which sets a flag. The loop checks the flag and the deadline before each model turn and each tool call, and stops with "Cancelled" or "Timed out". Without this, a local model could keep running for up to 8 turns × 300 s after the user gave up.
  2. **Build the context** (the system prompt), deterministic and ordered so it can be prompt-cached:
     - fixed instructions: role, output contract, "SELECT reads all graphs; in an UPDATE, the `INSERT` and `DELETE` templates must use `GRAPH <internal>` explicitly (not `WITH`, which would also hide the external graphs from the `WHERE` clause); the `WHERE` clause may read any graph", the placeholder convention, "add `LIMIT 200` to SELECT unless asked otherwise", "use `DELETE … INSERT … WHERE` for edits", "never write `dcterms:created` or `dcterms:modified`: the application maintains them" (decision 10);
     - **the app's naming convention**, stated explicitly. It is a fixed rule and is not subject to the precedence rule below: an entity's name is its `rico:name` literal (set by the app on every created entity), not a `rico:hasOrHadName` → `rico:Name` node. The fallback order for reading a name is the one in `RdfEntityService.bestLabel()` (`rico:name`, `rdfs:label`, `dcterms:title`, …). Expose that order as a public constant in `RdfEntityService`, used by `bestLabel()`, `search_entities` and this prompt, so it's defined in one place;
     - **how to use the tools**: "When the request refers to specific existing entities by name or description, look them up with `search_entities` before writing any query, and write UPDATEs against the IRIs you found, never against a label `FILTER`. Use `describe_entity` to see which properties an entity actually uses and where each value comes from. If no candidate or several plausible candidates are found, answer `CLARIFY` of kind `ENTITY`. A value whose `source` is external is read-only: to change it, add an internal value and say so in the explanation.";
     - **how to choose an encoding**, the precedence rule of decision 9, worded for the model: "To record a piece of information (a gender, a language, a role…), decide which property and which kind of value to use in this order:
       1. a history example marked *corrected* or *decided by the user*;
       2. how this project's data already records it: the usage profile below, or `find_usage`. If existing values fit, reuse them: link to the existing vocabulary entity, or use the existing literal form, matching across languages ('masculine' = 'masculin' = 'male'), rather than adding a new variant;
       3. the `mainProperties` / `mainTerminologies` of the configuration;
       4. the RiC-O definitions, using `search_ontology` when needed, for entities that have a RiC-O type.
       
       Only RiC-O is described to you. Properties of other ontologies may be used only as the project data already uses them: never introduce a property from another ontology that the usage profile does not show. Do not add a type from another ontology to an entity just to be able to use one of its properties. If after these steps two or more encodings remain plausible, or none is found, answer `CLARIFY` of kind `ENCODING`, with each option, its basis and an example triple. If the chosen encoding points to a vocabulary entity that does not exist yet (for example no `rico:DemographicGroup` named 'masculine'), create it with a placeholder IRI, its type and `rico:name`, in the same UPDATE, and say so in the explanation.";
     - `PREFIX` declarations for the namespaces present in the project. Prefixes come from `configuration.json` and the data, not from reading ontology files;
     - **the RiC-O digest** (section 1.11): the only ontology content in the prompt;
     - **the project usage profile** (section 1.12), which replaces the former type counts and "predicates of *UsedOnlyOntologies*";
     - `mainProperties` / `mainTerminologies` from `configuration.json`, as configuration and not as ontology content (for non-RiC-O namespaces only the property IRIs, with no definitions). The file mixes full IRIs and bare local names (e.g. `"Agent": ["hasName", "hasOrHadOccupation"]`): they are expanded with the same normalization `OntologyService` already applies. An entry that doesn't resolve to a RiC-O term is left out and logged. Checked against the current `rico.rdf`, three RiC-O entries don't resolve today:
       - `Agent: hasName` (the file has `hasOrHadName` and `name`);
       - `Agent: hasOrHadOccupation` (the file has `hasOrHadOccupationOfType`);
       - the terminology `ContentFormType`.
       
       These need fixing in `configuration.json`; this also affects the existing UI;
     - optionally, one **sample entity** per main type present in the project: the fullest one, rendered like `describe_entity`, with long texts truncated. It gives the model a concrete pattern to copy. Controlled by `fieldarchive.nlquery.sample-entities` (default 1, 0 to send no real record);
     - the graph layout: internal graph IRI, external source graphs (read-only), metadata graphs and the RiC-O ontology graph (never touch).
     
     - **how to use the history examples** (section 1.6): "Examples from this project's history show a question and the query that was actually run for it. Reuse their *encodings*: which class, property and graph pattern express which wording. Do not reuse their IRIs unless the same entity is clearly meant, and even then confirm it with `search_entities`. An example marked *corrected by the user* or *decided by the user* overrides any conflicting example or schema guess. When two examples disagree, prefer the newer one."
     
     The prompt text is the same for all LLM agents, with one exception: the local agent gets a **compact context**. Local models usually run with a context window of 8–32K tokens, and every extra token slows each turn on a laptop. The compact context contains:
     - **no RiC-O digest**: the local model reaches it through `search_ontology`;
     - the usage profile limited to the 10 most frequent types, and no sample entities (`find_usage` and `describe_entity` give the details on demand);
     - 3 history examples instead of 8;
     - tool results trimmed to 1 KB.
     
     `NlQueryService` builds either form from the same pieces, according to a `provider.contextBudget()` hint. It is built as two strings, in a fixed order so it can be prompt-cached:
     - `staticSystem`: instructions and the RiC-O digest. These are identical for every project and every question, so the cache is shared across projects too;
     - `projectSystem`: prefixes, configuration main lists, usage profile, sample entities and graph IRIs. They change only when the data changes.
     
     The user message holds, in order: the history examples selected for this question (they change with every question, so they come after the cacheable part), then the question. How each provider sends these strings and caches them is in section 1.7.
  3. **Agent loop** (provider-neutral, at most `max-turns` = 8 model turns): `session = provider.start(AgentRequest)`, then loop on `turn = session.next()`:
     - `TOOL_CALLS`: run every call (several may arrive in one turn), then `session.addToolResults(results)`, all results at once. A failing tool returns an error result, never an exception.
     - `FINAL`: parse the JSON into `NlQueryModelOutput`, then go to validation (step 4).
     - `REFUSED` or `TRUNCATED`, or the turn cap reached: throw a `BadRequestException` whose message names the agent ("ChatGPT declined this request", "Claude ran out of output space").
     - Tools (all read-only, the same six for every LLM agent, plus `submit_answer` for the local agent (section 1.9), results trimmed to a few KB so the context stays small). Their names, descriptions and parameter schemas are in `resources/nlquery/tools.json`, which all LLM providers read:
       - `search_entities(text, type)` (`type` may be `null`): matches `text` against the name properties in the `bestLabel` order, across **all** graphs. The comparison is case- and accent-insensitive and tolerates spelling variants, so "dupond" finds "Dupont": try the exact match first, then `CONTAINS` on each word, then compare the words by edit distance in Java. The optional `type` filter is expanded with `OntologyService.expandWithSubtypes`. Returns up to 20 `EntityMatchDto`, marking each as internal or external with `RdfEntityService.isInternalEntity`. Implemented in a new `RdfEntityService.searchByLabel`, which the UI could reuse later.
       - `describe_entity(iri)`: reuses `RdfEntityService.getByIri`, flattened to `predicate → [value (source, datasource)]` plus types. This shows the model which properties this entity *actually* uses, which settles vocabulary doubts from the real data.
       - `run_select(query)`: goes through `SparqlService.select` (so SELECT-only, with the parser check below), with `LIMIT 50` added when there isn't one, and a 10-second execution limit (`setMaxExecutionTime`). The usage-profile queries get the same limit. For anything the other two can't answer, such as "the recording made in Paris in 2019".
       - `search_ontology(text)`: searches the **RiC-O digest only** (section 1.11): labels in every language RiC-O provides, definitions and scope notes. Returns up to 15 entries (term, kind, domain → range, inverse, definition). Claude and ChatGPT already have the digest in their prompt and rarely need it; for the local agent it is the only way into RiC-O.
       - `find_usage(text_or_property, type)` (`type` may be `null`): how the project data records something. The argument is either a property IRI, or a word. A word is turned into candidate properties in two ways:
         - predicates used in the data whose local name contains it (so "gender" finds `foaf:gender`);
         - RiC-O properties whose digest entry matches it, including through their range class (so "gender" finds `rico:hasOrHadDemographicGroup`, whose range `rico:DemographicGroup` is defined with "gender, (biological) sex"). This reads the RiC-O digest only, consistent with decision 9.
         
         For each candidate actually used in the data, it returns, per subject type: the number of entities using it, the kind of value (literal datatype or entity type), and the recurring values with their count and name (section 1.12 for which values are shown). Example: `find_usage("gender", "rico:Person")` finds `rico:hasOrHadDemographicGroup` on 85 persons, with values "masculin" (40) and "féminin" (45) and their IRIs. This works for any namespace, which is how other ontologies' properties become known without mining them.
       - `search_history(text)`: returns up to 10 more history entries matching `text`, in the same format as the examples in the prompt. Useful when the question mixes several expressions and the pre-selected examples cover only some of them.
     - The `clarifications` from the request, if any, go into the user message as decisions already made: each `statement`, prefixed "Decided by the user:". The model must follow them and must not ask again.
  4. **Validate** (skipped for `CLARIFY`) with `QueryParserUtil.parseOperation(QueryLanguage.SPARQL, sparql, null)`:
     - The result must be a `ParsedTupleQuery` for SELECT or a `ParsedUpdate` for UPDATE, and must match `queryType`. ASK, CONSTRUCT and DESCRIBE are rejected: the SPARQL page can't display them.
     - For UPDATE, walk each `UpdateExpr` (`Modify`, `InsertData`, `DeleteData`, `Clear`, `Load`…). Reject `LOAD`, `CLEAR`, `DROP`, `CREATE`, `COPY`, `MOVE` and `ADD`, and `WITH`. Reject any insert or delete template whose context is not the internal graph, including the default graph. The `WHERE` clause is not restricted: reading external graphs to find what to change is allowed.
     - Reject writes of `dcterms:created` / `dcterms:modified` (decision 10), and every `newEntities` placeholder must appear in the query and vice versa.
     - For UPDATE, every concrete (non-placeholder) subject IRI written in an insert or delete must exist in the store. This catches IRIs the model made up instead of looking up.
     - For the Built-in agent there is no loop: a validation error is returned to the user as is (it points to a bug in a pattern or a history substitution).
     - On a parse or validation error, send the error back with `session.addUserText(...)`, in the same loop (it counts toward the 8-turn cap), so the model can fix it, looking things up again if needed. On a second validation failure, return the error together with the last query so the user sees what went wrong.
  5. **Replace placeholders**: each `urn:fieldarchive:new:N` becomes `<prefix>/<localName(type)>/<uuid>`. Extract the IRI-minting part of `RdfEntityService.iriFromKey` into a small public helper (for example `RdfEntityService.mintIri(typeIri)`) so both services use the same scheme. Set `createdEntityIris`, and set `targetEntityIri` to the first new entity or, failing that, the IRI the model resolved through its lookup. For edits, check that the model's `targetEntityIri` is one of the IRIs the query actually writes to.
  6. Nothing is executed here. Execution goes through the existing `/sparql` endpoints.

### 1.4 `controllers/NlQueryController` (`/nlquery`)
- `GET /nlquery/status` → `NlQueryStatusDto`
- `POST /nlquery/translate` → `NlQueryDto`
- `GET /nlquery/history` → the entries, newest first
- `DELETE /nlquery/history/{id}` → removes a bad example so it stops influencing the agent
- `POST /nlquery/cancel/{requestId}` → cancels a running translation
- `PUT /nlquery/keys`, `DELETE /nlquery/keys/{provider}` → section 1.8

### 1.5 Small fixes in `SparqlService`
- Replace the keyword checks in `select` / `update` with the same `QueryParserUtil.parseOperation` type check. Otherwise, a generated SELECT that filters on a literal containing "create" or "delete" is rejected.
- Make `update` run inside `conn.begin()` / `conn.commit()`, as the other services do.
- **Bookkeeping (decision 10)**, inside that transaction:
  - collect the statements the UPDATE added and removed in the internal graph, with an RDF4J `RepositoryConnectionListener` on a `NotifyingRepositoryConnection`, then:
  - subjects that had no statement in the internal graph before and have an `rdf:type` after get `dcterms:created` (now);
  - other subjects that were changed and still exist get `dcterms:modified` replaced (now);
  - if anything changed, the internal data source's `dcterms:modified` in `CTX_META` is refreshed. Move `touchInternalDataSource` from `RdfEntityService` to a small shared helper so both services use it.
  
  This applies to every UPDATE run through `/sparql/update`, including queries typed by hand in the SPARQL page: the data stays consistent whichever way it was changed. The same step publishes `ProjectDataChangedEvent` (section 1.12).

### 1.6 Query history (`services/NlQueryHistoryService`)

**File.** `projectsDirectory.getPublicPath().resolve(<current project name>).resolve("natural_language_query_history.json")`, next to `store/`. Because it lives in the project directory, it already moves with the project on rename (`ProjectService.updateProject` moves the whole directory) and disappears on delete (the directory is removed recursively). Format (Jackson, pretty-printed so it can be read and edited by hand):

```json
{
  "version": 1,
  "entries": [
    {
      "id": "uuid",
      "timestamp": "2026-09-29T14:03:11Z",
      "question": "Rename marie dupond to Marie Durand",
      "clarifications": [],
      "queryType": "UPDATE",
      "sparql": "PREFIX rico: <…> DELETE { GRAPH <…_internal> { <…/Person/…> rico:name ?old } } INSERT { … } WHERE { … }",
      "generatedSparql": null,
      "edited": false,
      "entities": [ { "iri": "…/Person/…", "label": "Marie Dupont" } ],
      "targetEntityIri": "…/Person/…",
      "createdEntityIris": [],
      "resultCount": null,
      "useCount": 1,
      "provider": "claude",
      "model": "claude-opus-5-5"
    }
  ]
}
```

- `clarifications` keeps the `NlClarification`s of the run. An entry with an `ENCODING` clarification is a **decision by the user**: when used as an example, it is weighted and labelled like a corrected entry ("decided by the user: record gender with …"). This way an encoding question is asked once per project, not every time.
- `sparql` is the query **as actually run**, with IRIs already minted. The frontend always sends the translated query as `nl.generatedSparql`. The backend stores it in the entry only when it differs from the query run (the user changed it in the SPARQL editor), and then sets `edited: true`: the pair is what the agent treats as a correction. Otherwise the entry has `generatedSparql: null`.
- `createdEntityIris` lists the IRIs minted for this run. When the entry is reused as an example, or by the Built-in agent, these IRIs are turned back into `<urn:fieldarchive:new:N>` placeholders, so a past "create" can never re-insert the same entity.
- `entities` holds the IRIs referenced in the query with their label at the time of recording, so the file is readable and an example still makes sense after a rename.
- `resultCount` is the row count for SELECT (0 is a useful signal: the encoding may be wrong).

**Recording: only when a query has actually run successfully.** Recording happens in the backend, in the same request as the execution, so nothing is recorded for a query that failed or was never run:
- `POST /sparql/select` and `POST /sparql/update` take a body `SparqlRequest { String query; NlHistoryContext nl; }`. The existing `{ "query": … }` JSON stays valid, because `nl` is optional. `NlHistoryContext` = `{ question, clarifications, generatedSparql, targetEntityIri, provider, model }`.
- After a successful execution with `nl` present, `SparqlController` calls `NlQueryHistoryService.record(...)`. It sets `edited` when the whitespace-normalized `query` differs from `generatedSparql`, and fills `entities` with `RdfEntityService.getNameOfEntityByIri`. A recording failure is logged and never fails the query.
- Deduplication: if an entry with the same normalized question and the same `sparql` exists, increment `useCount` and refresh `timestamp` instead of appending.
- Writing: a `synchronized` read-modify-write, written to `….json.tmp`, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. A missing file is treated as an empty history. An unreadable file is renamed `….json.corrupt-<timestamp>`, logged, and treated as empty, so a broken file never blocks the dialog.
- No size cap on the file. The prompt selection below is what stays bounded.

**Use by the agent.**
- `selectExamples(question, n = 8)`: normalize text (lowercase, accents removed, French and English stop words removed) and score each entry by word overlap (Jaccard) with the question. Add a bonus for `edited` entries and entries with an `ENCODING` decision, and a small bonus for `useCount` and recency, then keep the top `n` with a score > 0. With fewer than 3 hits, fill up with the most recent entries, so a new project still shows its house style. The same scoring backs the `search_history` tool.
- Each example is rendered as:

  ```
  Q: <question>   [corrected by the user | decided by the user: <statement>]   [SELECT, 0 results]
  Entities: <iri> = "<current label>" (or "no longer exists")
  SPARQL run: <sparql>
  (Generated before correction: <generatedSparql>)
  ```

  Labels are re-read at prompt time with `getNameOfEntityByIri`. That way the model sees current names, and an example about a deleted entity is still useful for its encoding, but is not mistaken for a live IRI.
- Examples are selected regardless of which agent produced them: an encoding is a fact about the project, not about the model. `provider` and `model` are kept in each entry for the History list and for diagnosing a bad example.
- Nothing is fine-tuned or stored at Anthropic or OpenAI (the OpenAI calls use `store: false`, section 1.7). The "learning" is entirely the choice of examples put into each request. Deleting an entry (`DELETE /nlquery/history/{id}`) or editing the file removes its influence immediately.
- The history is sent to the selected agent's API like the rest of the project context (the schema, and the tool results already contain project data), so it adds no new kind of data leaving the machine.

**Backups.**
- `FileExportService` (`export/backup`): add a zip entry `<projectName>/natural_language_query_history.json` when the file exists.
- `FileImportService.importBackup`: the zip loop currently `break`s as soon as it finds `project-backup.trig`. Instead, read every entry, and keep the history bytes when present. Once `initEmptyProjectRepository` has created the project directory, write them to the project's history file. A backup without the file restores as before.
- `export/internal` (Turtle) stays RDF-only.

### 1.7 Provider layer (`services/nlquery/llm/`)

```java
interface Agent {                // everything the drop-down lists
    String id();                 // "claude" | "openai" | "local" | "builtin"
    String label();
    String model();              // "—" for builtin
    ProviderStatusDto status();  // resolves the key (section 1.8), never returns it
}
interface LlmProvider extends Agent {
    ContextBudget contextBudget();   // FULL (Claude, ChatGPT) or COMPACT (local), section 1.3
    AgentSession start(AgentRequest request);
}
interface DirectAgent extends Agent {  // BuiltinAgent, section 1.10
    NlQueryModelOutput translate(String question, List<NlClarification> clarifications, AgentContext ctx);
}
record AgentRequest(String staticSystem, String projectSystem, String userMessage,
                    List<ToolSpec> tools, JsonNode outputSchema, String cacheKey /* project name */) {}
interface AgentSession {         // holds the provider-native transcript
    AgentTurn next();
    void addToolResults(List<ToolResult> results);
    void addUserText(String text);
}
record AgentTurn(Kind kind, List<ToolCall> toolCalls, String finalJson, Usage usage) {}   // Kind: TOOL_CALLS, FINAL, REFUSED, TRUNCATED
record ToolSpec(String name, String description, JsonNode parametersSchema) {}
record ToolCall(String id, String name, JsonNode arguments) {}
record ToolResult(String callId, String content, boolean isError) {}
```

`ClaudeProvider`, `OpenAiProvider` and `LocalProvider` (section 1.9) are the only classes that import an SDK. `BuiltinAgent` (section 1.10) implements `DirectAgent` and does not use the loop at all. Its output goes through the same validation, minting and history steps. Each session keeps its own native transcript, which is what lets both keep their reasoning state between turns (see the table).

**Schemas written once for all LLM providers.** `tools.json` and `output-schema.json` follow the stricter of the two rulesets (OpenAI strict mode), which Claude's `strict` tools and structured output also accept:
- every property is listed in `required`, and optional values are nullable (`"type": ["string", "null"]`);
- `additionalProperties: false` on every object;
- only plain types, `enum`, arrays and nested objects. No `format`, `pattern`, `minLength` or numeric bounds, which one side or the other ignores or rejects. `NlQueryService` checks those limits itself after parsing.

**How each concern maps:**

| Concern | `ClaudeProvider` (Messages API) | `OpenAiProvider` (Responses API) |
|---|---|---|
| Call | `client.messages().create(...)` | `client.responses().create(...)` |
| System prompt | two `system` blocks, `cache_control` on `staticSystem` | `instructions` = `staticSystem` + `projectSystem`, in that order |
| Prompt caching | explicit (breakpoint above) | automatic on the common prefix (≥ 1024 tokens), plus `prompt_cache_key` = project name to keep one project's calls on the same cache |
| Tools | custom tools with `strict: true`, `tool_choice: auto` (forced tool choice is rejected by `claude-opus-5-5`) | `function` tools with `strict: true`, `tool_choice: auto`, `parallel_tool_calls: true` |
| Final answer | `output_config.format` = JSON schema | `text.format` = `json_schema`, `strict: true` |
| Reasoning | adaptive thinking (default), `output_config.effort: medium` | `reasoning.effort: medium` |
| Keeping reasoning across turns | append `response.content` unchanged (thinking blocks must be passed back as-is) | `store: false` + `include: ["reasoning.encrypted_content"]`; append every output item (reasoning and `function_call`) unchanged to the next `input`. If the chosen model or organization cannot return encrypted reasoning, fall back to `store: true` + `previous_response_id` (responses then stay 30 days at OpenAI) and say so in `status().reason`. |
| Tool calls → `TOOL_CALLS` | `stop_reason == tool_use`; `tool_use` blocks | `function_call` items in `output` (`call_id`, JSON `arguments` string, parsed with Jackson) |
| Tool results | one user message containing every `tool_result`, `is_error: true` on failure | one `function_call_output` item per `call_id`; failures as `{"error": "…"}`, since there is no error flag |
| `addUserText` | user text message | user `message` input item |
| `FINAL` | `stop_reason == end_turn`, text block = JSON | `message` item with `output_text` = JSON |
| `REFUSED` | `stop_reason == refusal` | a `refusal` content part in the message |
| `TRUNCATED` | `stop_reason == max_tokens` | `status == incomplete`, reason `max_output_tokens` (reasoning tokens count toward this limit) |
| Refusal fallback | `fallbacks: "default"` (server-side beta); remove it if unwanted | none |
| Output limit | `max_tokens` = `max-output-tokens` | `max_output_tokens` = `max-output-tokens` |

**Errors.** Each provider catches its SDK's typed exceptions and turns them into the same messages: invalid key (401), no quota or rate limited (429: on OpenAI this is also the "no credit on the account" case, which new API users hit often), service unavailable (5xx, network). Messages name the agent, so the user knows which key or account to check.

**Usage.** Each turn's token usage is logged at debug level with the provider, to compare costs.

### 1.8 API keys (`services/nlquery/ApiKeyStore`)
Only Claude and ChatGPT use keys; the local and built-in agents never do.
- Resolution per provider: the environment variable (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`) if set, else `$FIELD_ARCHIVE_DATA/settings/llm-keys.json`:
  ```json
  { "claude": "sk-ant-…", "openai": "sk-…" }
  ```
- The file is under `settings/`, not under `projects/`, so it is never part of a project export or backup.
- It is written with the same temp-file-then-atomic-move scheme as the history, with owner-only permissions (`PosixFilePermissions` `rw-------` where the filesystem supports them).
- The key is stored in plain text in the user's application-data folder, like most desktop tools do. OS keychain storage (Electron `safeStorage`) is not reachable from the Java backend; it's a possible later improvement.
- Endpoints (in `NlQueryController`):
  - `PUT /nlquery/keys` (`SaveApiKeyRequest`): saves the key. Before saving, it validates the key with a cheap authenticated call (list models) and returns a readable error if the key is refused;
  - `DELETE /nlquery/keys/{provider}`.
  
  Both invalidate the provider's cached client. Neither endpoint ever returns a key.
- When a provider's key comes from the environment, the dialog shows "set by environment variable" and hides the edit field for that provider.

### 1.9 Local model agent (`LocalProvider`, via Ollama)

**What the user installs**:
- Ollama (a free installer for macOS, Windows and Linux);
- one model, with `ollama pull gpt-oss:20b`.

The default model is configurable. `gpt-oss:20b` supports tool calling and needs about 16 GB of RAM. A smaller tool-capable model (for example an 8B Qwen) can be set for modest machines, at the cost of more wrong queries. Which model to recommend in the manual is decided by running the verification scenarios on two or three candidates; the choice is not fixed here.

**Implementation: a subclass of `OpenAiProvider`.** Ollama serves an OpenAI-compatible Responses API at `http://localhost:11434/v1`, including function tools. `LocalProvider` is therefore `OpenAiProvider` built with `baseUrl(...)` and a dummy key, with these differences:

| Concern | `OpenAiProvider` | `LocalProvider` |
|---|---|---|
| State | `store: false` + encrypted reasoning items | Ollama supports only the stateless form, and has no `previous_response_id`: the full transcript is re-sent each turn, the same way. No `include`, no `store`. |
| Final answer | `text.format` = strict JSON schema | Structured output is not guaranteed through the compatibility API. The final answer is given as an extra tool, **`submit_answer`**, whose parameters are `output-schema.json`. Tool calling is what local models are trained for, and it's the most reliable way to get JSON out of them. A plain-text reply is also accepted if it parses as JSON. If neither, the parse error is sent back like a validation error (it uses a turn). |
| `strict` | yes | not sent. Arguments are checked against the schema by `NlQueryService` (they already are, for all providers). |
| Reasoning effort | `reasoning.effort: medium` | the model's own default |
| Prompt | full context | compact context (section 1.3) |
| Timeout | SDK default | `local.timeout-seconds` (default 300) per call. On CPU, one turn can take a minute. |
| Refusal / truncation | as in 1.7 | the same mapping; truncation is the likelier failure (small context) |

If the Responses endpoint proves unreliable with the chosen model, `LocalProvider` switches to `/v1/chat/completions`, which Ollama also supports with tools. Only this class changes.

**Status.** `GET http://localhost:11434/api/tags`, with a 1-second timeout:
- no answer → "Ollama is not running (install it from ollama.com)";
- the model is missing from the list → "Model gpt-oss:20b is not installed: run `ollama pull gpt-oss:20b`";
- otherwise available.

`GET /api/show` gives the model's context length. When it's below 16K, `reason` adds a warning about setting `OLLAMA_CONTEXT_LENGTH=32768`, because a too-small window silently cuts the prompt.

**Honest limits, stated in the dialog**: slower (tens of seconds to a few minutes per question on a laptop), and more `CLARIFY` answers and rejected queries than the paid agents. The validation step (section 1.3, step 4) is what keeps a weak model from damaging the project: a wrong UPDATE is rejected, and anything accepted is still shown for confirmation before it runs.

### 1.10 Built-in agent (`BuiltinAgent`, no AI)

A deterministic translator. It covers only a fixed set of question shapes, but it is always available, instant and free. It implements `DirectAgent` (section 1.7). `NlQueryService` calls it instead of the loop, then applies **the same** validation, IRI minting and history recording as for the LLM agents.

**Vocabulary: learned from the project, not hardcoded.**
- **Types**: for RiC-O, the digest's labels in every language RiC-O provides (English, French, Spanish…). For other namespaces, only the types present in the usage profile, matched by local name. Plurals are handled for both.
- **Properties**: RiC-O labels from the digest, and for every namespace the predicates in the usage profile (local name), limited to the `mainProperties` of the matched type and the predicates actually used in the project. No non-RiC-O ontology file is read, the same rule as for the LLM agents.
- **Values**: the "set a property" shape follows the same precedence rule. It reuses a recurring value or vocabulary entity from the usage profile when one matches by approximate spelling: "masculine" → the existing "masculin" entity. It has no translation knowledge, so "male" would not match "masculin". When nothing matches, it returns an `ENTITY` clarify listing the existing values. When the property has no usage yet, it returns an `ENCODING` clarify instead of guessing.
- **A small lexicon file**, `resources/nlquery/builtin-lexicon.json`, with French and English synonyms for the common cases. For example "personne/person/people" → `rico:Person`, "nom/name/named/appelé" → `rico:name`, "créé/created" → `dcterms:created`. The lexicon is editable, and the configured `mainTypes` are covered first.

Matching is done on normalized text (lowercase, accents removed).

**Supported shapes (v1)** — each is a regular expression over the normalized question, with slots filled from the vocabulary. The patterns exist in French and English; the English form is shown:

| Shape | Example | Result |
|---|---|---|
| list a type | "list all persons", "liste des personnes" | SELECT `?s ?name` of that type (subtypes included) |
| count a type | "how many records" | SELECT `COUNT` |
| filter by property | "persons named Dupont", "records whose title contains chant" | SELECT with `FILTER(CONTAINS(lcase(str(?v)), …))` |
| show an entity | "show Marie Dupont" | resolve with `search_entities` → SELECT of its properties, and `targetEntityIri` set |
| create | "create a person named Marie Dupont" | INSERT with a placeholder IRI, the type and `rico:name` |
| rename | "rename Marie Dupont to Marie Durand" | resolve → DELETE/INSERT of `rico:name` on that IRI |
| set a property | "set the description of X to …", "add the gender masculine to X" | resolve X and the property → DELETE/INSERT of a literal, or a link to an existing vocabulary entity (see *Values*) |
| delete | "delete Marie Dupont" | resolve → DELETE of X's triples in the internal graph |

Entity resolution uses the same `RdfEntityService.searchByLabel` as the `search_entities` tool, so the behaviour is the same as the LLM agents: no match or several matches → `CLARIFY` with the candidates.

**Learning from the history.** Before trying the patterns, `BuiltinAgent` looks for a history entry whose question has the same "shape" once entity names and quoted values are masked. For example, "rename ⟨X⟩ to ⟨Y⟩" matches a past "rename Marie Dupont to Marie Durand". If one is found, it reuses that entry's SPARQL, substituting the newly resolved IRIs and values, with a preference for `edited` entries. Slots are aligned through the entry's `entities` list (IRI ↔ label as it appeared in the question). Created IRIs are turned back into placeholders (section 1.6). This way a phrasing that a user once got through a paid or local agent, or corrected by hand, can later be handled for free. The substitution only applies when every masked slot resolves; otherwise the pattern route is tried.

**When nothing matches**, it returns `queryType: UNSUPPORTED` with an explanation, a few example phrasings it does understand (`examples`), and a suggestion to use another agent if one is available. This is a normal answer, not an HTTP error.

The answer's `lookups` field states which route was used ("pattern: rename" or "reused history entry of 2026-09-12"), so the user can see why a query came out as it did.

### 1.11 RiC-O digest (`services/nlquery/RicoDigestService`)
- **Only `rico.rdf` is read.** The other ontology files are not opened by any `nlquery` code: not the digest, not `search_ontology`, not the Built-in agent. `OntologyService` keeps parsing them for the UI as before; the agent layer does not use its schemas for other namespaces.
- Built once, lazily, from `rico.rdf` with RDF4J Rio, and kept in memory. It never changes while the backend runs, like `OntologyService.schemaCache`.
- One entry per RiC-O term, 447 terms in the current file (103 classes, 290 object and 54 datatype properties), in a fixed order: classes by hierarchy then alphabetically, then properties alphabetically. The fixed order keeps the prompt byte-identical, which is what makes it cacheable.

  ```
  rico:DemographicGroup  (class)  label: Demographic Group / Catégorie démographique   sub of: rico:Type
    def: … possible specific demographic groups are gender, (biological) sex, education …
  rico:hasOrHadDemographicGroup  (object property)  label: has or had demographic group / a ou a eu pour catégorie démographique
    (rico:Group | rico:Person) → rico:DemographicGroup   inverse: rico:isOrWasDemographicGroupOf   sub of: rico:hasOrHadCategory
    def: Connects a Person or a Group to a Demographic Group to which it belongs or belonged.
  ```
- **Content of each entry:**
  - English label, and the French label when present, since questions may be asked in French;
  - domain → range, and cardinality, taken from the existing `OntologySchemaExtractor` applied to `rico.rdf` only;
  - `owl:inverseOf` and direct superclasses or superproperties;
  - `rdfs:comment` and `skos:definition`;
  - `skos:scopeNote`, which can be switched off by configuration (`fieldarchive.nlquery.rico-scope-notes=true`). Measured on the current file: about 25K tokens without scope notes, about 40K with them.
- The digest size is logged at startup, and the entries are indexed for `search_ontology`. The search normalizes text the same way as the history search and matches labels in all languages, definitions and scope notes.

### 1.12 Project usage profile (`services/nlquery/UsageProfileService`)
How the project's data actually records things, for every namespace. It is the only way properties of ontologies other than RiC-O become known to the agent.

- **Computed with SPARQL** over the data graphs: the internal and external data-source graphs. It excludes `CTX_META`, the project metadata graph and the RiC-O ontology graph `CTX_ONTO_RICO`; otherwise RiC-O's own OWL triples would be counted as usage.
  - per type: the number of entities;
  - per (type, predicate): the number of subjects using it and the share of the type;
  - per (type, predicate): the value kind, either literal with datatype and language, or entity with the types of the targets;
  - per (type, predicate): the recurring values, with names from `bestLabel`.
- **Size and privacy limits**, so it stays a few KB and sends aggregates, not records:
  - the 30 most frequent types, and 25 predicates per type;
  - at most 8 values per predicate;
  - a literal value is listed only if it occurs **at least twice** and is shorter than 40 characters. An entity value is listed if it occurs at least twice, or if its type is a vocabulary type (a `mainTerminologies` type, or a subclass of `rico:Type` such as `rico:DemographicGroup`), so that a vocabulary entry used once can still be reused. Categorical values such as "masculin" or "fr" therefore appear, while names, notes and transcriptions never do: they are unique or long.
- Rendered as:

  ```
  rico:Person (120)
    rico:name                          120 (100%)  text
    rico:hasOrHadDemographicGroup       85  (71%)  → rico:DemographicGroup: "masculin" <iri> (40), "féminin" <iri> (45)
    foaf:gender                         12  (10%)  text: "male" (7), "female" (5)   [external: census_2019]
  ```
  
  Predicates found only in external data sources are marked with the source name. This way the agent can see that a FOAF property exists in imported data, and weigh that against the internal practice.
- **Cached per project** in memory and invalidated on any data change. Every service that writes publishes a Spring `ProjectDataChangedEvent`: `SparqlService.update`, `RdfEntityService` writes, `DataSourceService` import, sync and delete, and project open or close. This keeps the profile exact after each applied update. The event is also the hook for the history's entity labels.
- The same queries, restricted to one predicate or word, back the `find_usage` tool.

### 1.13 What leaves the computer
To state in the manual, and to check against the privacy claims above:
- **Claude and ChatGPT** receive:
  - the question and the clarifications;
  - the RiC-O digest;
  - the usage profile (aggregates);
  - the sample entities, if `sample-entities` > 0;
  - the history examples (past questions and queries, with entity labels);
  - **the results of the tools the model calls**. `describe_entity`, `search_entities` and `run_select` return real records: names, dates, notes, trimmed to a few KB per call.
  
  Nothing is sent before the user presses Enter or Translate.
- **Local model and Built-in**: nothing leaves the computer.

## 2. Frontend

### 2.1 `services/nl-query.service.ts` (new)
- `status()`, `translate(requestId, provider, question, clarifications)`, `cancel(requestId)`, `saveKey(provider, apiKey)`, `deleteKey(provider)` → `http://localhost:8080/nlquery/...` (hardcoded base URL, like the other services).
- `pendingSparql$`: a `BehaviorSubject<{ query, type: 'SELECT' | 'UPDATE', autoRun: boolean, nl: NlHistoryContext } | null>`, the hand-off to the SPARQL page. A subject rather than a plain field, because navigating to `/sparql` while already on `/sparql` does not re-create the component (Angular's default), so an `ngOnInit` read would miss it. The consumer sets it back to `null` after reading.
- `history()`, `deleteHistoryEntry(id)`.

### 2.2 `GestionRessourcesService`
- Uncomment and fix `runUpdateQuery(query, nl?)` → `POST /sparql/update`, and give `runSelectQuery(query, nl?)` the same optional `nl`, sent as `{ query, nl }`. Calls without `nl` (every existing caller) behave exactly as today and are never recorded.

### 2.3 `components/nl-query-dialog` (new, standalone, `MatDialog`)
Dialog data: `{ initialQuestion?: string }`. When `initialQuestion` is set (opened from the header input, section 2.4), the dialog puts it in the textarea. It then waits for `status()`, selects the agent by the usual rule below, and **starts the translation immediately**. The dialog is open, with its spinner, from the first moment; it doesn't wait for the answer. Opened without it (the header's icon), the dialog starts empty, as before.

Layout, top to bottom:
- **Agent drop-down** (`mat-select`, first row of the dialog), filled from `status().providers`. Each option shows the label and, in small print, the model, e.g. "ChatGPT (OpenAI) · gpt-6-astra".
  - An unavailable provider stays in the list with its `reason`, and a "Set API key" link that opens an inline field (password input, **Save** → `saveKey`, then `status()` is reloaded). A saved key can be replaced or removed from the same place.
  - Order: Claude, ChatGPT, Local model, Built-in. The last two carry a "free" badge, and the local one also a "stays on this computer" badge. Unavailable local model → its `reason` (not running, model missing) and a link to the manual's setup section, instead of a key field. Built-in is always available; its option shows "limited set of questions" and a small "What can I ask?" link that lists the supported shapes.
  - Initial choice: the last agent used, remembered in `localStorage` (wrapped in try/catch) if it is still available, else the first available one. Built-in is always available, so the dialog never ends up without an agent. With no *paid* agent available, the key fields are offered below the drop-down, with one line on where to get each key and a reminder that a ChatGPT subscription is not an API key.
  - The drop-down is disabled while a translation is running. The spinner shows elapsed time and a **Cancel** button, which is important for the local agent. Cancel calls `cancel(requestId)`, so the backend stops at its next turn or tool call, then unsubscribes. Changing agent clears the current result and the clarifications collected so far, but keeps the question.
- a textarea for the question (Ctrl/Cmd+Enter submits) and a **Translate** button with a spinner. Several lookups can take a few seconds, so the spinner label says "Looking up entities and writing the query…";
- after translation: "Generated by <label> · <model>", the explanation, a type badge (SELECT = neutral, UPDATE = amber "modifies the project"), a read-only monospace block with the SPARQL, a **Copy** button, the list of entities that will be created (if any), the entity the update targets (label and IRI), and a collapsible "What I looked up" list showing `lookups`;
- **`CLARIFY` answer**, two kinds:
  - `ENTITY`: show the explanation (e.g. "Two persons match 'Marie Dupond'. Which one?") and the `candidates` as a selectable list with label, types and an internal/external badge. With no candidate ("No person named Jean Nobody"), only the explanation and a "Rephrase" button are shown.
  - `UNSUPPORTED` (Built-in only): the explanation, the `examples` as clickable chips that fill the textarea, and a link to switch to another available agent.
  - `ENCODING`: show the explanation (e.g. "This project does not record gender yet. How should it be recorded?") and the `encodingOptions` as radio cards. Each card has the label, the description, a badge for its basis ("used in this project" / "configuration" / "RiC-O definition") and the example triple in monospace. A note says the choice will be remembered for this project.
  - Picking an entity or an option appends an `NlClarification` to the list kept by the dialog, and calls `translate(selectedProvider, question, clarifications)` again. A question can go through several rounds. "None of these" returns to the textarea so the question can be rephrased;
- actions: **Run** (label "Run query" or "Apply changes"), **Open in SPARQL editor** (SELECT: hand off without running; UPDATE: hand off with `selectedType = 'UPDATE'`), **Cancel**;
- errors are shown inline in the dialog. For key, quota and rate-limit errors, add a hint to try the other agent, if one is available;
- a **History** section below the textarea, collapsed by default: the most recent entries (question, type, a "corrected" badge, the agent that generated it, date). Clicking an entry puts its question back in the textarea. A delete icon calls `deleteHistoryEntry` after a confirmation, the way to get rid of an example that taught the agent something wrong.

The `nl` context sent with every run is `{ question, clarifications, generatedSparql: <the translated sparql>, targetEntityIri, provider, model }`.

Run behaviour:
- **SELECT**: `pendingSparql$.next({ query, type: 'SELECT', autoRun: true, nl })`, close the dialog, `router.navigate(['/sparql'])`.
- **Open in SPARQL editor**: the same hand-off with `autoRun: false`. When the user then runs the query (possibly edited), it is recorded with the edited text as `sparql` and the original as `generatedSparql`.
- **UPDATE**: `runUpdateQuery(sparql, nl)`. On success:
  - if `targetEntityIri` is set, check that it still exists (`GET /rdf/entity?key=`; a delete makes it disappear). Load `getTypes()` for `ontologyLabels`, close this dialog, then open `EntityDetailsComponent` with the same size options and `data` as `GestionRessourcesComponent.openEntityDetailsDialog()`;
  - otherwise (bulk edit, delete, or no identifiable entity): show a snackbar ("Update applied") and close.
  - After **every** successful UPDATE from a question, whether or not the entity dialog opens, emit `GestionRessourcesService.entitiesChanged$`. `GestionRessourcesComponent` subscribes and reloads its current type list, so the list doesn't go stale whatever page the dialog was opened from. This one mechanism replaces refreshing on the entity dialog's `afterClosed()`.

Move the entity-dialog opening options into a helper (for example `openEntityDetails(dialog, ontologyLabels, iri)` in `GestionRessourcesService`) so the page and the new dialog don't duplicate them.

### 2.4 Header (`nav-bar`): the question input
A single-line input directly in the header banner, so that a question can be typed without opening the dialog first. It replaces the separate "Ask" button.

- **Placement**: between the left block (logo and project name) and the navigation buttons, taking the free space (`flex-1`, `max-w-xl`, centered). Styled like the header's buttons: translucent white background (`bg-white/10`, `focus:bg-white/20`), white text, rounded, with a sparkle icon on the left. Placeholder: "Ask about this project… (e.g. list all persons)".
- **Enter**:
  - with text: open `NlQueryDialogComponent` with `data: { initialQuestion }` (`width: '720px', maxWidth: '95vw'`), then clear the input. The dialog shows the question and its first proposal, from the last-used agent (section 2.3);
  - empty: open the dialog empty, like a click on the sparkle icon.
  
  The input is single-line, so Enter always submits. Escape clears the input and removes focus.
- **Sparkle icon**: a button inside the input that opens the dialog with whatever has been typed, or empty. This is the mouse path, and the replacement for the "Ask" button.
- **Recent questions**: a `mat-autocomplete` under the input, listing up to 5 recent history questions that contain the typed words. It's loaded once when the input gets focus (`GET /nlquery/history`), and cleared when the project changes. Picking one fills the input; Enter then submits it. This is a convenience, and does not bypass the dialog: re-running still goes through translate, then confirmation.
- **Shortcut**: Ctrl/Cmd+K focuses the input from anywhere in the app (a `@HostListener('document:keydown')` in `NavBarComponent`), shown as a small "⌘K" hint at the right end of the empty input.
- **No project open**: the input is `disabled`, greyed like the project-dependent buttons, with the tooltip "Open a project first".
- **Narrow windows** (below `lg`): the input collapses to the sparkle icon alone, which opens the empty dialog, so the header never wraps.
- The dialog is modal, so while it is open the header input can't be used. There is no second dialog and no competing question.

### 2.5 `SparqlComponent`
- Inject `NlQueryService` and subscribe to `pendingSparql$` (unsubscribe on destroy). On a non-null value: copy `query` and `selectedType`, keep `nl` in a private `nlContext` field, set the subject back to `null`, and call `runQuery()` when `autoRun` is true. `runQuery()` passes `nlContext` to `runSelectQuery` / `runUpdateQuery` and clears it after the first **successful** run, so later manual queries on the page are not recorded. `clearAll()` and `loadExample()` also clear it. A small "From question: …" note above the editor shows while `nlContext` is set.
- After a successful UPDATE run with an `nlContext` (the "Open in SPARQL editor" path), behave like the dialog's Run: emit `entitiesChanged$`, and open the entity dialog on `nlContext.targetEntityIri` if it still exists, through the shared `openEntityDetails` helper. An UPDATE decided from a question ends the same way whichever button ran it.
- Implement the missing UPDATE branch of `runQuery()` with `runUpdateQuery` (sets `updateSuccess`). The template already has an `updateSuccess` state.
- Stop the `.replace(/\s+/g, ' ')` normalisation from breaking queries with `#` comments, which the model may emit: newlines are what end a comment. Either keep the newlines or tell the model not to emit comments. Keeping the newlines is safer.

## 3. Files touched

| Tier | File | Change |
|---|---|---|
| Backend | `pom.xml`, `application.properties` | both SDK dependencies (versions aligned), per-provider model config |
| Backend | `services/nlquery/llm/LlmProvider.java`, `AgentSession.java`, `AgentTurn.java` + records, `ClaudeProvider.java`, `OpenAiProvider.java` | new: provider layer, the only code importing an SDK |
| Backend | `services/nlquery/ApiKeyStore.java`, `dtos/SaveApiKeyRequest.java`, `dtos/ProviderStatusDto.java` | new: key resolution and storage |
| Backend | `resources/nlquery/tools.json`, `resources/nlquery/output-schema.json` | new: schemas shared by all LLM providers (`submit_answer` for the local one reuses `output-schema.json`) |
| Backend | `services/nlquery/llm/LocalProvider.java` | new: Ollama through the OpenAI SDK, status checks |
| Backend | `services/nlquery/RicoDigestService.java` | new: RiC-O-only digest and `search_ontology` index |
| Backend | `services/nlquery/UsageProfileService.java`, `ProjectDataChangedEvent.java` (+ publishing in `SparqlService`, `RdfEntityService`, `DataSourceService`, `ProjectService`) | new: usage profile, `find_usage`, invalidation |
| Backend | `dtos/NlClarification.java`, `dtos/EncodingOptionDto.java` | new |
| Backend | `services/nlquery/builtin/BuiltinAgent.java`, `DirectAgent.java`, `resources/nlquery/builtin-lexicon.json` | new: pattern-based agent, history-shape reuse |
| Backend | `dtos/NlQuery*.java` | new |
| Backend | `services/NlQueryService.java`, `controllers/NlQueryController.java` | new |
| Backend | `services/RdfEntityService.java` | extract `mintIri(typeIri)`; public label-property order constant; new `searchByLabel(text, type)` |
| Backend | `dtos/EntityMatchDto.java` | new |
| Backend | `services/NlQueryHistoryService.java`, `dtos/NlHistoryEntry.java`, `dtos/NlHistoryContext.java`, `dtos/SparqlRequest.java` | new: history file, recording, example selection |
| Backend | `controllers/SparqlController.java` | `SparqlRequest` body; record after a successful run |
| Backend | `services/FileExportService.java`, `services/FileImportService.java` | history file in backup zip; import no longer stops at the first entry |
| Backend | `services/SparqlService.java` | parser-based classification, transaction, bookkeeping triples (decision 10), change event |
| Backend | `services/RdfEntityService.java` (again) | `touchInternalDataSource` moved to a shared helper |
| Frontend | `services/nl-query.service.ts` | new |
| Frontend | `components/nl-query-dialog/*` | new |
| Frontend | `components/nav-bar/*` | question input in the header (Enter opens the dialog with a first proposal), recent-question autocomplete, Ctrl/Cmd+K |
| Frontend | `components/sparql/sparql.component.ts` | consume hand-off, UPDATE branch, keep newlines |
| Frontend | `services/gestion-ressources.service.ts` | `runUpdateQuery`, `openEntityDetails` helper, `entitiesChanged$` |
| Frontend | `components/gestion-ressources/*.ts` | use the helper, refresh on change |
| Docs | `CLAUDE.md`, `Doc/Manual.md` | new endpoint row and provider layer; `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` in Commands; user-facing section: choosing an agent, getting an API key (and that a ChatGPT subscription is not one), installing Ollama and a model, the Built-in agent's supported phrasings |

## 4. Verification

- `cd Backend && mvn clean package -DskipTests` and `cd Frontend && npx ng build --configuration development`.
- `mvn dependency:tree | grep -E "jackson|okhttp|kotlin-stdlib"`: one version of each.
- Keys and status:
  - No key at all and Ollama not running: the backend starts, `GET /nlquery/status` → Claude, ChatGPT and Local `available: false`, Built-in `available: true`. The dialog selects Built-in and offers the key fields.
  - Only an OpenAI key: ChatGPT is selected by default, Claude is greyed with "No Anthropic API key". The same with only a Claude key.
  - Save a key from the dialog → `settings/llm-keys.json` created with owner-only permissions, the provider becomes available without a restart, and `status` never contains the key. Save an invalid key → refused with a readable message, nothing written.
  - With `ANTHROPIC_API_KEY` exported and the app started from a terminal, Claude shows "set by environment variable".
  - Start the packaged app from the Dock with a key saved in settings → it works (the case environment variables can't cover).
- `curl -X POST localhost:8080/nlquery/translate -H 'Content-Type: application/json' -d '{"provider":"openai","question":"list all persons with their names"}'` → a parseable SELECT, and the same with `"provider":"claude"`.
- **Run every scenario below with Claude, ChatGPT and the local model.** The loop, tools, validation and history are shared, so any difference comes from the provider layer or the model. For the local model, note which scenarios fail with each candidate model; this decides the recommended model and what the manual promises. **The Built-in agent must pass every scenario marked (B).**
  - "Which records were created in 2019?" → Run → `/sparql` opens with the query in the editor and the results table filled in.
  - (B) "Create a person named Marie Dupont" → the UPDATE shows one placeholder-derived IRI under the project prefix and a `GRAPH <…_internal>` clause → Apply → `entity-details` opens on the new person with `rico:name` = "Marie Dupont", badged *internal*.
  - (B) "Rename marie dupond to Marie Durand" (lower case, misspelled) → the lookups trace shows `search_entities` finding "Marie Dupont". The UPDATE uses her IRI, with no label `FILTER` → Apply → `entity-details` opens on that entity with the new value.
  - (B) Create a second "Marie Dupont", then ask for the same rename → a `CLARIFY` answer with both candidates → pick one → the UPDATE targets only that IRI.
  - Rename an entity whose `rico:name` comes only from an external source → the explanation says the external value is read-only, and the UPDATE adds an internal `rico:name`.
  - (B) "Rename Jean Nobody to X" (no such entity) → `CLARIFY` with no candidates, and no UPDATE is proposed.
  - "Delete all persons" → Apply → no entity dialog, only a snackbar. Check that entities which also exist in an external source keep their external triples.
  - (B, as far as it applies) Adversarial checks: "clear the external source X" is rejected by validation, and so is a generated insert into an external graph.
  - A SELECT whose filter literal contains "delete" now runs through `/sparql/select`.
- Vocabulary and encoding (with Claude, ChatGPT and the local model; (B) where marked):
  - The prompt (logged at debug level) contains RiC-O definitions and **no** FOAF, SKOS, PNV, BIO, BIBO or BIBFRAME definitions. Nothing under `nlquery` opens a file other than `rico.rdf`: check with a breakpoint or a log in the file-loading code.
  - The digest is byte-identical across two questions and two projects. Claude's `cache_read_input_tokens` > 0 on a question in a second project.
  - (B) Project where 80+ persons use `rico:hasOrHadDemographicGroup` with a "masculin" entity: "add the gender 'masculine' to Jean Dupond" → an UPDATE linking Jean Dupond to the **existing** "masculin" entity, with no new entity and no text value. `lookups` shows the profile or `find_usage` as the basis.
  - Fresh project, no gender anywhere: the same request → `CLARIFY` of kind `ENCODING` with options based on RiC-O (demographic group) and on configuration if any. There is no FOAF option, since FOAF is not mined and not used in the data. Pick the RiC-O option → an UPDATE creating a `rico:DemographicGroup` "masculine" with a placeholder IRI and linking Jean Dupond to it. After running it, ask "add the gender 'feminine' to Marie Durand" → no clarify, same encoding (decision in the history, and now also in the profile).
  - Project whose external source uses `foaf:gender` (e.g. an imported census): the profile shows `foaf:gender` marked as external. On a person with no internal gender, the agent either follows the internal practice if there is one, or offers an `ENCODING` clarify listing "`foaf:gender` (used in imported data)" as an option. It never adds a `foaf:Person` type just to use `foaf:gender`.
  - Usage profile privacy: a project with long notes and unique names → the rendered profile contains no note text and no names used only once.
  - Apply an UPDATE, then ask again → the profile reflects the change (event-driven invalidation).
  - Local model: "gender" questions go through `search_ontology` and `find_usage` calls (visible in `lookups`), since the digest is not in its prompt.
- Consistency and robustness:
  - Create an entity from a question → it has `dcterms:created`, shows its creation date in the entity list, and the internal data source's `dcterms:modified` has moved. Rename it from a question → `dcterms:modified` is set. Delete it from a question → the same triples are gone as with the UI's delete. Same checks for an UPDATE typed by hand in the SPARQL page.
  - A generated UPDATE that writes `dcterms:modified` itself, or uses `WITH` → rejected by validation.
  - "Set the language of every recording to the language of its source record" (a `WHERE` reading an external graph, templates writing to the internal one) → accepted.
  - Start a slow translation (local model), open another project from the Projects page meanwhile → the translation stops with "The project changed…" and nothing is written anywhere.
  - Cancel a local-model translation → the backend log shows it stopped at the next turn, not after the timeout.
  - An `ENTITY` clarify whose model output contains an IRI that doesn't exist → rejected (candidates must exist); the dialog never shows an invented label.
  - Navigate to `/sparql`, then ask a SELECT from the header while on that page → the editor updates and runs (the `pendingSparql$` subject).
- Header input:
  - With a project open, type "list all persons" in the header and press Enter → the dialog opens at once with the question and a spinner, and the proposal appears without any further click, from the last-used agent. The header input is empty again.
  - Enter on an empty input, or a click on the sparkle icon → an empty dialog. Escape clears the input.
  - No project open → the input is disabled with the tooltip. Ctrl/Cmd+K on any page focuses the input.
  - Type a word from an earlier question → it's suggested; pick it and press Enter → it goes through a fresh translation.
  - Narrow the window below `lg` → only the icon remains, and the header doesn't wrap.
  - A first proposal that is a `CLARIFY` → the dialog shows the choices as usual.
- Free agents:
  - Local model, Ollama not running → "not running"; running without the model → the `ollama pull` hint; with the model → available, with no key anywhere and no network access needed. Unplug the network and run a scenario to confirm.
  - Local model: the final answer arrives through `submit_answer`. Make it reply in plain text once (for example with a tiny model) → the reply is either parsed or sent back as an error, and never crashes the loop.
  - Local model with a 4K context (`OLLAMA_CONTEXT_LENGTH=4096`) → the status warning is shown.
  - Built-in: "list all persons", "liste des personnes", "how many records", "persons named Dupont", "show Marie Dupont", "delete Marie Dupont" produce the expected queries. "Who influenced whom in the 1950s?" → `UNSUPPORTED` with examples.
  - Built-in reusing a past "create a person named X" entry for "create a person named Y" → a new placeholder IRI, never the IRI minted for X.
  - Built-in learning: have ChatGPT or Claude produce and run "list the recordings of Marie Dupont", then ask Built-in "list the recordings of Jean Martin" → it reuses that entry with Jean Martin's IRI, and `lookups` says so.
- Provider-specific checks:
  - A question that makes the model call two tools in one turn (e.g. "compare the recordings of Marie Dupont and Jean Martin") → both results go back in one turn, for both providers.
  - OpenAI: debug logs show `store: false` and the reasoning items being sent back on turn 2. Claude: `cache_read_input_tokens` > 0 on the second question in the same project. OpenAI: `cached_tokens` > 0 on the second question.
  - Invalid key, and an OpenAI account with no credit (429) → an error naming the agent, with the hint to try the other one.
  - `CLARIFY` with ChatGPT, then switch to Claude before picking a candidate → the second pass works (requests are self-contained).
- History:
  - Run a translated SELECT → `natural_language_query_history.json` appears next to `store/` with one entry. Translate without running, or run a query that fails → no entry. Run a query typed by hand in the SPARQL page → no entry.
  - Run the same question twice → one entry with `useCount: 2`.
  - "Open in SPARQL editor", change a property in the query, run it → an entry with `edited: true` and both queries. Then ask a similarly worded question → the translation follows the corrected encoding. Check that the example appeared in the prompt by logging the selected examples at debug level.
  - Delete that entry from the dialog's History section → the next translation no longer follows it.
  - Rename the project → the history file follows. Export a backup and import it under a fresh data directory → the history file is restored. Importing an older backup without the file still works.
  - Record a corrected query using ChatGPT, then ask a similar question with Claude → Claude follows the correction (the history is shared), and the entry shows "ChatGPT" in the History list.
  - Corrupt the JSON by hand → the dialog still works, the file is renamed `.corrupt-…`, and a new history starts.

## 5. Out of scope / v2
- Using `search_entities` in the UI itself: a global search box in the header.
- Conversation history in the dialog ("now only the women"), which could reuse the previous turn's query.
- Streaming the explanation while the query is being generated.
- More providers. The `LlmProvider` interface is the extension point, but tool calling would have to be checked for each one.
- A "Download model" button that runs `ollama pull` through Ollama's API, with a progress bar.
- More Built-in shapes (date ranges, relations between two entities), guided by which questions users ask it and it fails.
- Keys in the OS keychain instead of `llm-keys.json`.

## 6. Implementation notes (differences from the plan above)
- **JSON Schema validation**: a small in-house `SchemaValidator` (the subset used by `tools.json` / `output-schema.json`) instead of the `com.networknt:json-schema-validator` dependency.
- **Output schema without nulls**: absent values are empty strings / empty lists and `clarifyKind` has a `NONE` value, so the schema needs no nullable types (safer across Claude, OpenAI strict mode and local models).
- **OpenAI**: the fallback to `store: true` + `previous_response_id` when encrypted reasoning is unavailable is not implemented; such an account gets a readable error.
- **Local model**: the context-length warning is not implemented: Ollama's API does not report the configured context window (`OLLAMA_CONTEXT_LENGTH`), only the model's maximum. The manual recommends 32K.
- **RiC-O digest size**: measured at 566 terms, ~240K characters (~60K tokens) with scope notes, more than the 25–40K estimated; `fieldarchive.nlquery.rico-scope-notes=false` reduces it.
- **Entity dialog helper**: `EntityDetailsDialogService` rather than a method of `GestionRessourcesService` (that service is imported by `EntityDetailsComponent`, which would create a circular import).
- **Built-in history replay**: entries that also created vocabulary entries (e.g. a new "masculine" group) are not replayed; the patterns reuse the existing entry instead of creating a duplicate. For updates, entity labels in the history are read before the update runs (a rename changes them).
