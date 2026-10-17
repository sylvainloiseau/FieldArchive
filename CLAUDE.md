# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

FieldArchive is a desktop application for describing linguistic fieldwork archives in RDF. `README.md` and `Doc/Manual.md` describe the application from the end user's point of view; this file describes the code.

## Repository layout

| Directory | Role |
|---|---|
| `Backend/` | Spring Boot 4 REST API (Java 17), owns all RDF logic and the RDF4J triplestore |
| `Frontend/` | Angular 19 standalone app (Angular Material + Tailwind), the whole UI |
| `electron/` | Electron main process: spawns the backend JAR, loads the Angular build, file dialogs |
| `Backend/src/main/resources/ontologies/` | The OWL/RDFS files of the *DefinedOntologies* + `configuration.json` |
| `samples/`, `Backend/data/`, `Doc/` | Sample RDF exports, scratch data, design notes |
| `RDF_Back/`, `Backend/src/.../example/`, `utils/CsvFileHandler.java` | Leftovers, not wired into the running app |

## Commands

Run backend and frontend in two terminals; Electron is only needed when testing the desktop packaging.

```bash
# Backend (port 8080) — FIELD_ARCHIVE_DATA is MANDATORY, the app throws at startup without it
cd Backend && FIELD_ARCHIVE_DATA=~/Library/Application\ Support/field_archive mvn spring-boot:run
# optional, for the natural-language agents: ANTHROPIC_API_KEY / OPENAI_API_KEY (or keys saved from the dialog);
# the local agent needs Ollama running with the model of fieldarchive.nlquery.local.model
cd Backend && mvn clean package -DskipTests      # produces target/Backend-0.0.1-SNAPSHOT.jar
cd Backend && mvn test -Dtest='*Test'            # the data matching tests (in-memory store, no FIELD_ARCHIVE_DATA needed);
                                                 # RdfBackApplicationTests.contextLoads needs FIELD_ARCHIVE_DATA
cd Backend && mvn test -Dtest=RdfBackApplicationTests#contextLoads   # single test

# Frontend (port 4200)
cd Frontend && npm install && ng serve
cd Frontend && npm test                          # Karma/Jasmine; only 2 generated specs exist
cd Frontend && ng test --include='**/nav-bar.component.spec.ts'      # single spec file

# Electron / packaging (from the repo root)
npm run build:all        # ng build into electron/dist/frontend + mvn package
npx electron .           # dev mode (loads localhost:4200) if not packaged
npm run dist             # electron-builder → dist-electron/ (dmg / exe / AppImage)
```

There is no linter configured and essentially no test suite; verification is manual through the UI or with `curl` against the endpoints below.

## Runtime architecture

Three processes, no database other than the triplestore:

```
Electron main (electron/main.js)
  ├─ spawn java -jar Backend.jar   env FIELD_ARCHIVE_DATA=app.getPath('userData')
  └─ BrowserWindow → localhost:4200 (dev) | packaged Angular build (prod)
        └─ Angular services → HTTP → localhost:8080 → RDF4J NativeStore on disk
```

- **Every project is its own RDF4J `NativeStore`**, at `$FIELD_ARCHIVE_DATA/projects/<projectName>/store`. `ProjectService.createRepository()` builds it; the list of projects is just the set of directories containing a `store/` subdirectory.
- **`core/ProjectContext` is a static, process-global singleton** holding the single open `Repository` plus its project name. Every service starts with `ProjectContext.getRepository()` / `requireProjectOpen()`. Consequences to keep in mind: only one project can be open at a time, opening a project closes the previous one, and there is no per-request or per-user isolation.
- `config/Rdf4jConfig` still declares a `Repository` bean pointing at `./data/rdf-store`. **It is never injected anywhere** — do not mistake it for the live store.
- CORS is open to `http://localhost:4200` only (`config/WebConfig`).

### REST endpoints (mounted at the root, *not* under `/api`)

`@RequestMapping` values are `/projects`, `/datasources`, `/rdf`, `/ontology`, `/sparql`, `/nlquery`, `/matching`, `/matching/configuration`. The `/api/...` prefix used in `README.md` (and in Electron's `waitForBackend` probe) does not exist — the probe only works because any response below HTTP 500, including the 404, is treated as "backend up". The Angular services hardcode `http://localhost:8080/<area>` individually; there is no `environment.ts` and no shared API-base constant.

| Controller | Path | Notes |
|---|---|---|
| `ProjectController` | `/projects` | `open`, `create`, `current`, `close`, `list/details`, `PUT /{old}` (rename), `DELETE /{name}`, `import`, `import-backup`, `export/internal`, `export/backup` |
| `DataSourceController` | `/datasources` | CRUD by `shortName`; `POST /external` is multipart (`dataSource` JSON part + `file`); `POST /{shortName}/sync` re-imports |
| `RdfEntityController` | `/rdf` | `GET /entities?type=` (omit `type` → entities with no `rdf:type`), `GET|PUT|DELETE /entity?key=<full IRI>`, `POST /entities` |
| `OntologyController` | `/ontology` | `types` (type navigator payload), `schema?namespace=`, `labels` CRUD, `known*` (RiC-O loaded in-store) |
| `SparqlController` | `/sparql` | `select` and `update`, body `SparqlRequest {query, nl?}`; operations are classified with RDF4J's parser. When `nl` is present the run is recorded in the question history. Every update also gets the bookkeeping triples (see below) |
| `NlQueryController` | `/nlquery` | `status`, `translate`, `cancel/{requestId}`, `history` (GET, DELETE `/{id}`), `keys` (PUT, DELETE `/{provider}`) |
| `MatchingController` | `/matching` | `overview`, `POST runs`, `links` (GET with filters, `POST` manual link, `POST /{id}/actions` and `/actions` with `ACCEPT`/`REJECT`/`UNDO`, `PUT /{id}/note`, `DELETE /orphaned`), `compare`, `entities`, `clusters`. Every link change takes `?dryRun=true` |
| `MatchingConfigurationController` | `/matching/configuration` | GET/PUT/DELETE (reset), `default`, `validate`, `vocabulary/types`, `vocabulary/predicates?types=`, `coverage`, `preview`, `export`, `import` (parses, does not save) |

## Data model: named graphs are the backbone

Everything the app knows about a project lives in the project's own store, split across named graphs (RDF4J "contexts"):

| Graph IRI | Written by | Content |
|---|---|---|
| `urn:datasource:<projectName>_internal` | `DataSourceService.createInternalDataSource` | The *InternalDataSource*: all user-created/editable triples |
| `urn:datasource:<shortName>` | idem, for each external source | One *ExternalDataSource*: read-only imported triples |
| `http://cnrs.lacito/app#/context/metadata` (`RdfContexts.CTX_META`) | `DataSourceService`, `OntologyService`, `BuiltinOntologyService` | Data-source descriptors (`app:shortName`, `app:graph`, `app:sourceType`, `app:editable`, `app:lastSync`, `app:sourceLocation`…) and declared ontology namespaces |
| `http://cnrs.lacito/app#/projects#<name>/metadata` | `ProjectService.upsertProjectMetadata` | The project itself: `rdfs:label`, `dcterms:created/modified/description`, `app:prefix` |
| `http://cnrs.lacito/app#/context/ontology/rico` (`CTX_ONTO_RICO`) | `BuiltinOntologyService.ensureRicoLoaded` | The full RiC-O OWL file, loaded into every project at open/create |
| `http://cnrs.lacito/app#/context/matching` (`CTX_MATCHING`) | `SameAsGraphWriter` (data matching) | `owl:sameAs` in both directions between every two IRIs of a cluster of matched entities. Derived from `data_matching.json`, rewritten after every link change and at project open; never edit it by hand |

Key invariants encoded in the services:

- The internal data source is **always named `<projectName>_internal`**; `RdfEntityService`, `FileImportService` and `FileExportService` all recompute it that way via `dsService.getGraphIri(projectName + "_internal")`.
- Editability is derived, not stored per triple: an entity is "internal"/editable iff it has at least one statement in the internal graph (`RdfEntityService.isInternalEntity`). Writes and deletes are restricted to that graph, so an entity coexisting in several sources keeps its external triples intact.
- `getByIri` reads statements across **all** graphs (except `CTX_MATCHING`, returned separately as `sameAs`) and tags each value and each type with `source` (`internal`/`external`) and `datasourceShortName` (resolved through `DataSourceService.getShortNameByGraph`). That is what lets the UI show all sources merged while badging their origin.
- New entity IRIs are `<project prefix>/<typeLocalName>/<uuid>` (`RdfEntityService.iriFromKey` / `mintIri`); the *key* used by the `/rdf/entity` endpoints is the full IRI, despite the parameter name.
- Bookkeeping triples are maintained by the backend whatever the write path (`InternalDataBookkeeping`): `dcterms:created` on entities new to the internal graph, `dcterms:modified` on changed ones, and the internal data source's `dcterms:modified` in `CTX_META`. For SPARQL updates, `SparqlService.update` collects the touched subjects with a `SailConnectionListener`.
- Every data write publishes a Spring `ProjectDataChangedEvent` (entity service, SPARQL update, data source import/sync/delete, backup import); the natural-language usage profile cache listens to it.
- `RdfEntityService.LABEL_PREDICATES` (`rico:name`, `rdfs:label`, `dcterms:title`, `foaf:name`) is the single definition of "an entity's name" (`bestLabel`, `searchByLabel`, the names of linked entities in `getByIri` — read in every graph, "" when none —, agent prompt).
- Export: `export/internal` writes the internal graph as context-free **Turtle**; `export/backup` writes a zip containing `<projectName>/project-backup.trig` with the internal graph + both metadata graphs + `CTX_MATCHING` as **quads**, plus `natural_language_query_history.json`, `data_matching.json` and `matching-configuration.json`. RiC-O is deliberately excluded from the backup and reloaded on restore. External source graphs are not in the backup either: after a restore they must be re-synced (until then their links show as orphaned).
- `ProjectService.openProject`/`createProject` and `FileImportService.importBackup` publish a `ProjectOpenedEvent`; the data matching listens to it to reconcile the inferred links and rewrite `CTX_MATCHING`.

## Ontology handling

The application distinguishes ontologies it understands from ontologies it merely encounters:

- ***DefinedOntologies*** — an ontology that has **both** an OWL/RDF file in `Backend/src/main/resources/ontologies/` **and** an entry in `ontologies/configuration.json`. Having only one of the two means the ontology is ignored. Currently: RiC-O, FOAF, SKOS, PNV, BIO, BIBO, BIBFRAME.
- ***UsedOnlyOntologies*** — namespaces met in project data (typically via an *ExternalDataSource*) that are not defined. Their classes and properties cannot be offered in the UI because nothing is known about them.
- ***ProjectOntologies*** — the ontologies actually present in a project: at least RiC-O (always loaded), plus whichever defined or used-only ontologies appear in the data.

`configuration.json` is keyed by namespace and, per ontology, carries `name`, `url`, `description`, `file`, and the UI-parameter blocks: **`mainTypes`** (the types to surface first in the type navigator), **`mainTerminologies`** (taxonomy types such as `rico:EventType`, used to build controlled vocabularies of reusable entities), **`mainProperties`** (per-domain shortlists that act as a form template for a type), plus `removeProperty` / `contextProperty`. Keys may be written with or without the trailing `#`/`/`; `OntologyService.normalizeNamespace` strips it, so compare namespaces normalized.

Pipeline: `OntologyService` loads `configuration.json` in its constructor and eagerly parses every declared file (`preloadSchemas`), caching one `OntologySchemaDto` per namespace in `schemaCache` (never invalidated — restart the backend after editing an ontology file or the JSON). The parsing itself is in the stateless `OntologySchemaExtractor`, which produces:

- `types`: named `owl:Class` / `rdfs:Class` in the namespace;
- `properties`: one `OntologyPropertyDto` **per (property, domain) pair**, with `kind` (`OBJECT_PROPERTY`/`DATA_PROPERTY`, inferred from the range for bare `rdf:Property` as in FOAF), resolved ranges (flattening `owl:unionOf` collections), datatype category for literals, and cardinality merged from `owl:FunctionalProperty` and `owl:Restriction` (`min/max/qualified` cardinality, with `owl:onClass`/`owl:allValuesFrom` overriding the global range for that domain);
- `hierarchy`: class URI → direct named superclasses, which is what allows the UI to offer a property declared on a superclass (e.g. an `rico:Agent` property when editing a `rico:Person`).

`GET /ontology/types` merges, per ontology, the configured `mainTypes`/`mainTerminologies`, the types actually **used** in the project (SPARQL `DISTINCT ?type`), the remaining defined types, the property list and the hierarchy — this single payload drives the *TypeNavigator*. `RdfEntityService.getByIri` also attaches the matching `OntologyPropertyDto` to each property as `schema`, so the editor knows the widget, range and cardinality to render.

## Backend component relations

```
Controllers (thin)
  ProjectController ──▶ ProjectService ──▶ ProjectContext (static Repository)
        │                    ├─▶ BuiltinOntologyService.ensureRicoLoaded()
        │                    └─▶ DataSourceService.createInternalDataSource()
        ├──▶ FileImportService ──▶ DataSourceService.getGraphIri()  (+ ProjectService.initEmptyProjectRepository for backups)
        └──▶ FileExportService ──▶ DataSourceService / ProjectService (graph IRIs)
  DataSourceController ─▶ DataSourceService ──▶ FileImportService (external source import)
  RdfEntityController ──▶ RdfEntityService ──▶ ProjectService (prefix, name)
                                             ├─▶ DataSourceService (internal graph, graph→shortName)
                                             └─▶ OntologyService (property schema)
  OntologyController ──▶ OntologyService (configuration.json + file parsing, in-memory)
                      └─▶ BuiltinOntologyService (RiC-O stored in the triplestore)
  SparqlController ────▶ SparqlService
```

### Natural-language queries (`services/nlquery/`)

A question typed in the header field (or the `nl-query-dialog`) is translated into SPARQL by an agent chosen in the dialog; nothing is executed by `/nlquery/translate`, the query runs through `/sparql` once the user confirms. The design is in `.claude/plans/natural-language-query-dialog.md`.

- `llm/`: `Agent` (what the drop-down lists), `LlmProvider` (the LLM loop contract) and `DirectAgent`. `ClaudeProvider` (Anthropic Messages API), `OpenAiProvider` (OpenAI Responses API, stateless with encrypted reasoning) and `LocalProvider` (subclass of `OpenAiProvider` pointed at Ollama, final answer through a `submit_answer` tool) are the only classes importing an SDK. `builtin/BuiltinAgent` is a pattern-based translator (no AI) that also replays history entries of the same shape.
- `NlQueryService`: provider-neutral loop (≤ `max-turns`), tool execution (`NlQueryTools`, definitions in `resources/nlquery/tools.json`), answer validation (`SchemaValidator` against `output-schema.json`, `SparqlGuard`: writes only in the internal graph with explicit `GRAPH`, no `WITH`/`LOAD`/`CLEAR`…, no bookkeeping triples, written subjects must exist), placeholder `<urn:fieldarchive:new:N>` → minted IRIs, cancel/deadline/project-change guard.
- `NlPromptBuilder`: static part (instructions + `RicoDigestService` text, cacheable) / project part (prefixes, configuration main lists, `UsageProfileService` profile, sample entities, graph layout) / user message (history examples, user decisions, question).
- **Only `rico.rdf` is read by the agent layer.** Other ontologies are known to the agents only through the SPARQL usage profile of the project data (`UsageProfileService`, `find_usage`).
- `NlQueryHistoryService`: `<project dir>/natural_language_query_history.json`, recorded by `SparqlController` after a successful run, included in backups by `FileExportService` / `FileImportService`.
- `ApiKeyStore`: `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`, else `$FIELD_ARCHIVE_DATA/settings/llm-keys.json` (saved from the dialog). Models and limits are in `application.properties` (`fieldarchive.nlquery.*`).

`ProjectService ↔ DataSourceService` and `DataSourceService ↔ FileImportService` are cyclic and broken with `@Lazy` on the constructor parameter — keep that annotation if you touch those constructors. Exceptions thrown by services (`BadRequestException`, `NotFoundException`, `ConflictException` in `exception/`) are mapped by `exception/ApiExceptionHandler`; the separate `exceptions/ImportException` package is a parallel, older one.

### Data matching (`services/matching/`)

Finds, reviews and remembers the IRIs of different data sources that denote the same entity. The design and the audit of the original notebook are in `.claude/plans/data-matching.md`.

- **Configuration** (`config/`): `<project dir>/matching-configuration.json`, else the bundled `resources/matching/default-matching-configuration.json`. *Type groups* of equivalent classes, each with *concepts* (predicates + comparator + weight + required/blocking). `MatchingConfigurationLoader` accepts the notebook's short form, CURIEs and missing ids; `MatchingConfigurationWriter` writes the full form with CURIEs; `MatchingConfigurationValidator` reports errors/warnings by JSON path; `MatchingConfigurationService` caches by file mtime and compiles to the immutable `MatchingConfiguration` (records). `MatchingVocabularyService` feeds the settings page.
- **Algorithm** (pure Java, no Spring): `profile/` (`EntityProfileLoader` reads one graph; `ValueResolver` turns values into `ComparableValue`s, a linked entity becoming its text or date; `TypeGroupResolver` decides the group under which two entities are compared), `candidate/` (`MatchingExclusions`, `BlockingIndex`, `CandidateGenerator`, `ProposalSelection`), `scoring/` (comparators, `PairScorer`: weighted mean of the comparable concepts, gated by `requiredConcepts` and `minimumComparedWeight`). `MatchingEngine` chains them; used by a run and by `MatchingPreviewService`.
- **Links** (`data_matching.json`, `MatchStore`): one record per pair of nodes `(dataSource, iri)`, statuses `PROPOSED`, `ACCEPTED`, `REJECTED`, `MANUAL`, `INFERRED`. `MatchClusters` (union-find over confirmed links + identical IRIs) enforces the invariants: at most one IRI per source in a cluster, no rejected pair inside a cluster (violations → 409). `MatchReconciler` maintains the `INFERRED` links after every change. A run only replaces the `PROPOSED` links of its pair.
- `MatchingService` holds one lock for every change; it listens to `ProjectDataChangedEvent` (profile cache) and `ProjectOpenedEvent` (reconcile + rewrite `CTX_MATCHING`). `IdenticalIriFinder` and `DataSourceCatalog` read the store directly.
- Tests: `src/test/java/.../services/matching/` (unit + `MatchingServiceScenarioTest` on `src/test/resources/matching/fixture.trig`, converted from the notebook's CSVs by `Data Matching/csv2trig.py`).

## Frontend component relations

Routes (`app.routes.ts`): `/gestion-projets` (default), `/gestion-sources`, `/gestion-ressources`, `/sparql`, `/data-matching`, `/data-matching/settings` (guarded against leaving with unsaved changes), `/files`. All components are standalone; dialogs are opened with `MatDialog`, not routed.

- **`gestion-projets`** — project management page: list/create/open/rename/delete, import a backup, export the internal source (Turtle) or a full backup (zip). Talks to `GestionProjetService`, which also holds the app-wide `activeProject$` `BehaviorSubject`.
- **`gestion-sources`** — *ExternalDataSource* management (create with file, edit, delete, re-sync) via `DataSourceHttpService`.
- **`gestion-ressources`** — the main workspace. Left: the **TypeNavigator**, built from `GET /ontology/types`, grouping types by ontology (RiC-O forced first, and the only one expanded at first) into main types / used types / terminologies, as a compact collapsible list, plus "Not typed". Right: `liste-entites` for the selected type, plus the `sparql` view. Opens `entity-details` as a dialog.
- **`liste-entites`** — sortable/filterable table of `RdfEntitySummaryDto`; entry point for creating an entity of the selected type and for opening one.
- **`entity-details`** (largest component, ~1100 lines) — the entity editor, a dialog. Header with the entity's name (`displayName`) and IRI; "Same as" and type chips (one chip per type, also outside the configured ontologies: `editEntity` sends the internal chips back as the complete list of internal types); one tab per configured ontology (label with the number of properties having a value). Each tab is a property sheet: rico:name, then the configured `mainProperties` (reshaped into `main_Properties` keyed by type), then the "Other properties". The value list is **one** `ng-template` (`#valueList`) used by the three sections; IRI properties get a native `select` whose options are cached per set of ranges (`rangeOptions`, preloaded for the visible tab) and a "Create" button that creates an entity of the range and links it. Property widgets are driven by the `schema` attached to each property. Adding a property is only possible in a tab whose ontology is *defined* **and** in which the entity declares a type. Delegates to `create-entity` (types / new entity; at most one type per ontology), `rico-properties` (choose a property, filtered by the kind of value), `range-selection-dialog`, the shared `confirm-dialog`, and `file-viewer`.
- **`file-viewer`** — previews a local image/video/audio/PDF referenced by an entity (a dialog from the editor, `{ path, readOnly }`, or the routed `/files` page); picking a file uses `window.electronAPI.selectFile()` (preload bridge), so it only works inside Electron (a notice says so elsewhere).
- **`shared/ui/`** — `confirm-dialog` (title, message, label/value details, `warn`) and `source-badge` (internal / external source with its name). Global classes in `styles.css`: `.act` (small icon action buttons), `.section-label`, and red `color="warn"` buttons (the Material 3 prebuilt theme ignores `warn`). Tailwind v4's default border colour is `currentColor`: give borders an explicit colour (`border-gray-200`).
- **`ontology-manager-dialog`** — edits the *client-side* namespace→label map in `OntologyLabelsService` (seeded from `models/ontology-labels.ts`). Note this is frontend-only state and is distinct from the backend `/ontology/labels` endpoints.
- **`nav-bar`** — the shared header: project name, navigation, and the natural-language question field (Enter opens `nl-query-dialog` with a first proposal; recent-question autocomplete; Ctrl/⌘+K).
- **`nl-query-dialog`** — agent drop-down (status from `/nlquery/status`, API key fields), question, proposal (SPARQL, explanation, lookups), CLARIFY rounds (which entity / which encoding), Run / Apply changes / Open in SPARQL editor, history list. A SELECT is handed to the SPARQL page through `NlQueryService.pendingSparql$`; an UPDATE is run, then the target entity is opened with `EntityDetailsDialogService`.
- **`data-matching`** — the review page (opened from `gestion-sources`): `matching-pair-list` (pairs of sources, counts, stale flag) and `matching-review-panel` (tabs by status, filters, evidence, keyboard review, bulk accept), plus the `matching-comparison-dialog`, `manual-match-dialog`, `matching-clusters-dialog` and `link-change-confirm-dialog`. Every link change goes through `MatchingActionsService`: dry run → confirmation when it has side effects → apply → snackbar with Undo.
- **`matching-settings`** — edits the matching configuration: defaults, type groups (drag to reorder), `matching-group-editor` (types, overrides, concept table with coverage, suggested predicates), `iri-chip-input`, a JSON view, import/export, and `matching-preview-panel`. Validation runs on the backend, debounced.
- `entity-details` shows a read-only "Same as" row from `RdfEntityDto.sameAs`.
- `type-selector` is an empty scaffold.

Services: `NlQueryService` (natural-language endpoints, `pendingSparql$` hand-off to the SPARQL page), `EntityDetailsDialogService` (opens the entity editor from anywhere), `GestionProjetService` (projects, import/export, snackbar error handling), `GestionProjetsService` (a one-method duplicate returning the current project), `DataSourceHttpService` (data sources, keeps a `dataSources$` subject and maps backend field names to the frontend `DataSource` model), `GestionRessourcesService` (types, ontology schema, entities CRUD **and** raw SPARQL), `OntologyLabelsService` (local labels), `MatchingHttpService` / `MatchingSettingsHttpService` / `MatchingActionsService` (data matching; models in `models/matching.model.ts`). `data-source.service.ts` and `data-source-mock.service.ts` are entirely commented out.

## Conventions

- **Backend packages**: `controllers` (thin, no RDF code) → `services` (all RDF4J work, transactions with `conn.begin()`/`conn.commit()`) → `dtos`; shared constants in `core` (`RdfNamespaces.APP` = `http://cnrs.lacito/app#`, `RdfNamespaces.RICO`, `RdfContexts`), Spring config in `config`, `utils` for helpers.
- **DTO naming**: `*Dto` for responses, `*Request` for request bodies, `Create*Request` / `Update*Request` for writes. Response DTOs mostly use public fields; a few (`OntologySchemaDto`, `OntologyPropertyDto`, `Create*Request`) use getters/setters — match whichever style the class already uses. Lombok is on the classpath but barely used.
- **IRI helpers inside services** follow a consistent shorthand: `pXxx()` builds a predicate IRI, `typeXxx()` a class IRI, `xxxCtx()` / `graphCtx()` a named-graph IRI, `dsIri()` a data-source subject IRI. Add new vocabulary terms as such helpers rather than inlining strings.
- **Frontend**: directories and selectors are kebab-case, several page names are French (`gestion-*`); classes are `XxxComponent` / `XxxService`; models are interfaces in `src/app/models`. Components are standalone with explicit `imports:`; styling mixes Angular Material and Tailwind utility classes. Backend payloads are typically handled as `any`, and services log liberally with emoji-prefixed `console.log`.
- **Language**: comments, log messages and user-facing error strings are a mix of French and English throughout both tiers. Prefer English for anything new, but don't mass-translate existing code.
