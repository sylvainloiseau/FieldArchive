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
cd Backend && mvn clean package -DskipTests      # produces target/Backend-0.0.1-SNAPSHOT.jar
cd Backend && mvn test                           # only RdfBackApplicationTests.contextLoads exists
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

`@RequestMapping` values are `/projects`, `/datasources`, `/rdf`, `/ontology`, `/sparql`. The `/api/...` prefix used in `README.md` (and in Electron's `waitForBackend` probe) does not exist — the probe only works because any response below HTTP 500, including the 404, is treated as "backend up". The Angular services hardcode `http://localhost:8080/<area>` individually; there is no `environment.ts` and no shared API-base constant.

| Controller | Path | Notes |
|---|---|---|
| `ProjectController` | `/projects` | `open`, `create`, `current`, `close`, `list/details`, `PUT /{old}` (rename), `DELETE /{name}`, `import`, `import-backup`, `export/internal`, `export/backup` |
| `DataSourceController` | `/datasources` | CRUD by `shortName`; `POST /external` is multipart (`dataSource` JSON part + `file`); `POST /{shortName}/sync` re-imports |
| `RdfEntityController` | `/rdf` | `GET /entities?type=` (omit `type` → entities with no `rdf:type`), `GET|PUT|DELETE /entity?key=<full IRI>`, `POST /entities` |
| `OntologyController` | `/ontology` | `types` (type navigator payload), `schema?namespace=`, `labels` CRUD, `known*` (RiC-O loaded in-store) |
| `SparqlController` | `/sparql` | `select` and `update`; `SparqlService` separates them by naive lowercase keyword matching on the query string |

## Data model: named graphs are the backbone

Everything the app knows about a project lives in the project's own store, split across named graphs (RDF4J "contexts"):

| Graph IRI | Written by | Content |
|---|---|---|
| `urn:datasource:<projectName>_internal` | `DataSourceService.createInternalDataSource` | The *InternalDataSource*: all user-created/editable triples |
| `urn:datasource:<shortName>` | idem, for each external source | One *ExternalDataSource*: read-only imported triples |
| `http://cnrs.lacito/app#/context/metadata` (`RdfContexts.CTX_META`) | `DataSourceService`, `OntologyService`, `BuiltinOntologyService` | Data-source descriptors (`app:shortName`, `app:graph`, `app:sourceType`, `app:editable`, `app:lastSync`, `app:sourceLocation`…) and declared ontology namespaces |
| `http://cnrs.lacito/app#/projects#<name>/metadata` | `ProjectService.upsertProjectMetadata` | The project itself: `rdfs:label`, `dcterms:created/modified/description`, `app:prefix` |
| `http://cnrs.lacito/app#/context/ontology/rico` (`CTX_ONTO_RICO`) | `BuiltinOntologyService.ensureRicoLoaded` | The full RiC-O OWL file, loaded into every project at open/create |

Key invariants encoded in the services:

- The internal data source is **always named `<projectName>_internal`**; `RdfEntityService`, `FileImportService` and `FileExportService` all recompute it that way via `dsService.getGraphIri(projectName + "_internal")`.
- Editability is derived, not stored per triple: an entity is "internal"/editable iff it has at least one statement in the internal graph (`RdfEntityService.isInternalEntity`). Writes and deletes are restricted to that graph, so an entity coexisting in several sources keeps its external triples intact.
- `getByIri` reads statements across **all** graphs and tags each value and each type with `source` (`internal`/`external`) and `datasourceShortName` (resolved through `DataSourceService.getShortNameByGraph`). That is what lets the UI show all sources merged while badging their origin.
- New entity IRIs are `<project prefix>/<typeLocalName>/<uuid>` (`RdfEntityService.iriFromKey`); the *key* used by the `/rdf/entity` endpoints is the full IRI, despite the parameter name.
- Export: `export/internal` writes the internal graph as context-free **Turtle**; `export/backup` writes a zip containing `<projectName>/project-backup.trig` with the internal graph + both metadata graphs as **quads**. RiC-O is deliberately excluded from the backup and reloaded on restore.

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

`ProjectService ↔ DataSourceService` and `DataSourceService ↔ FileImportService` are cyclic and broken with `@Lazy` on the constructor parameter — keep that annotation if you touch those constructors. Exceptions thrown by services (`BadRequestException`, `NotFoundException`, `ConflictException` in `exception/`) are mapped by `exception/ApiExceptionHandler`; the separate `exceptions/ImportException` package is a parallel, older one.

## Frontend component relations

Routes (`app.routes.ts`): `/gestion-projets` (default), `/gestion-sources`, `/gestion-ressources`, `/files`. All components are standalone; dialogs are opened with `MatDialog`, not routed.

- **`gestion-projets`** — project management page: list/create/open/rename/delete, import a backup, export the internal source (Turtle) or a full backup (zip). Talks to `GestionProjetService`, which also holds the app-wide `activeProject$` `BehaviorSubject`.
- **`gestion-sources`** — *ExternalDataSource* management (create with file, edit, delete, re-sync) via `DataSourceHttpService`.
- **`gestion-ressources`** — the main workspace. Left: the **TypeNavigator**, built from `GET /ontology/types`, grouping types by ontology (RiC-O forced first) into main types / used types / terminologies. Right: `liste-entites` for the selected type, plus the `sparql` view. Opens `entity-details` as a dialog.
- **`liste-entites`** — sortable/filterable table of `RdfEntitySummaryDto`; entry point for creating an entity of the selected type and for opening one.
- **`entity-details`** (largest component, ~1000 lines) — the entity editor. One tab per ontology present on the entity; the configured `mainProperties` are reshaped into `main_Properties` keyed by type and rendered above the "Other Properties" block; property widgets are driven by the `schema` attached to each property (literal input vs. entity dropdown filtered by range, cardinality limits, source badge). Adding a property is only possible in a tab whose ontology is *defined* **and** in which the entity declares a type. Delegates to `create-entity` (pick at most one type per ontology, offered as Main Types / Main Terminologies / defined types), `rico-properties` + `range-selection-dialog` (pick a property by navigating its range), `confirm-delete-dialog` / `confirm-delete-property`, and `file-viewer`.
- **`file-viewer`** — previews a local image/video/audio/PDF referenced by an entity; uses `window.electronAPI.selectFile()` (preload bridge), so it only fully works inside Electron.
- **`ontology-manager-dialog`** — edits the *client-side* namespace→label map in `OntologyLabelsService` (seeded from `models/ontology-labels.ts`). Note this is frontend-only state and is distinct from the backend `/ontology/labels` endpoints.
- `nav-bar` and `type-selector` are empty scaffolds.

Services: `GestionProjetService` (projects, import/export, snackbar error handling), `GestionProjetsService` (a one-method duplicate returning the current project), `DataSourceHttpService` (data sources, keeps a `dataSources$` subject and maps backend field names to the frontend `DataSource` model), `GestionRessourcesService` (types, ontology schema, entities CRUD **and** raw SPARQL), `OntologyLabelsService` (local labels). `data-source.service.ts` and `data-source-mock.service.ts` are entirely commented out.

## Conventions

- **Backend packages**: `controllers` (thin, no RDF code) → `services` (all RDF4J work, transactions with `conn.begin()`/`conn.commit()`) → `dtos`; shared constants in `core` (`RdfNamespaces.APP` = `http://cnrs.lacito/app#`, `RdfNamespaces.RICO`, `RdfContexts`), Spring config in `config`, `utils` for helpers.
- **DTO naming**: `*Dto` for responses, `*Request` for request bodies, `Create*Request` / `Update*Request` for writes. Response DTOs mostly use public fields; a few (`OntologySchemaDto`, `OntologyPropertyDto`, `Create*Request`) use getters/setters — match whichever style the class already uses. Lombok is on the classpath but barely used.
- **IRI helpers inside services** follow a consistent shorthand: `pXxx()` builds a predicate IRI, `typeXxx()` a class IRI, `xxxCtx()` / `graphCtx()` a named-graph IRI, `dsIri()` a data-source subject IRI. Add new vocabulary terms as such helpers rather than inlining strings.
- **Frontend**: directories and selectors are kebab-case, several page names are French (`gestion-*`); classes are `XxxComponent` / `XxxService`; models are interfaces in `src/app/models`. Components are standalone with explicit `imports:`; styling mixes Angular Material and Tailwind utility classes. Backend payloads are typically handled as `any`, and services log liberally with emoji-prefixed `console.log`.
- **Language**: comments, log messages and user-facing error strings are a mix of French and English throughout both tiers. Prefer English for anything new, but don't mass-translate existing code.
