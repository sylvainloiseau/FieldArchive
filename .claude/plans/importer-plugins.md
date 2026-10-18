# Importer plugins (ricImporterPlugin) for external data sources

Status: **implemented (2026-10-17).** Plugin API and lameta2rico changes done and deployed (§6).
Differences from the plan as written:
- the plugin options store only non-default values, and an unknown option name is dropped;
- the RDF-file importer resolves relative IRIs against the project prefix both at creation and on
  re-import (before, a re-import used "" while the creation used the prefix);
- `DataSourceService` no longer depends on `FileImportService` (the cycle is gone; creation goes
  through `importIntoSource`);
- a corrupt jar is reported as "Not a valid jar file"; a plugin whose name is `rdf-file` is refused;
- an external source can now also be created from a path alone (no upload), for RDF files too.
Not done: the suggestions of §4 (preview before replacing, API additions beyond `inputKind`,
releases, a plugins panel).

v2: rewritten after reading the lameta plugin (`../lameta2rico`) in detail. The core of the
design is now explicit: **the plugin's statements go straight from the converter into the
project's triplestore, in one transaction; no file and no intermediate model is written.**

## 1. Goal

An external data source is filled by an *importer*: either "Sync with an RDF file" (today's
behaviour) or a *plugin* converting another application's data (first: a lameta project
directory) into RiC-O. Plugins are jars implementing the `ricImporterPlugin` API, found at
backend startup in two `ricImporterPlugins` directories and loaded with `URLClassLoader`. The
data source panel gets back its "Source" drop-down; Import runs the chosen importer on the path
and replaces the source's named graph.

## 2. Findings

### 2.1 The plugin API (`../ricImporterPlugin`, `io.github.sylvainloiseau:ricimporterplugin:0.1.0-SNAPSHOT`)

- Service interface: **`fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider`**
  (`name()`, `description()`, `options()`, `create(Path input, ConverterOptions)`).
  `Lameta2RicoConverterProvider` is lameta's implementation of it.
- `RdfConverter.convert(RDFHandler)` **pushes** the graph into any RDF4J `RDFHandler`:
  `startRDF`, namespaces, statements (without context), `endRDF`. The converter never chooses
  the destination. This is what makes a file unnecessary: FieldArchive passes a handler that
  writes into the store.
- `RepositoryLoader.load(conn, converter, ctx, replace)` does that with RDF4J's `RDFInserter`
  and joins a transaction already open on `conn`. FieldArchive will use its own equivalent
  (§3.3) so that the RDF-file importer and the plugins share one sink, but the principle is the
  same.
- `ConverterOptions` requires a base IRI; `RdfConversionException` reports input problems.
- Built on RDF4J 5.2.2, the backend's version: plugin and host share the same RDF4J classes.

### 2.2 The lameta plugin (`../lameta2rico`)

- **Coordinates** (renamed in commit 7ff1f33): `io.github.sylvainloiseau:lameta2rico:0.1.0-SNAPSHOT`,
  published on GitHub Packages and resolvable. Classes in `io.github.sylvainloiseau.lameta2rico`
  (`Lameta2RicoConverter`, `Lameta2RicoConverterProvider`), registered in
  `META-INF/services/fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider` (the API package
  itself is unchanged). The provider's `name()` is still **`"lameta"`**: that is the id shown in
  the drop-down and stored in `app:sourceTool`, and it matches the old `Lameta` values (Q2).
- Artifacts: the plain jar (191 KB: its classes + RiC-O file; needs the API, `rdf4j-model`,
  `rdf4j-model-vocabulary`, `rdf4j-rio-api`, `slf4j-api`, all present in the backend once the
  API is a dependency) and a shaded `-cli` jar (12 MB, with its own RDF4J, picocli,
  slf4j-simple). **Ship the plain jar.**
- `Lameta2RicoConverter`: input = the project **directory** (validated in the constructor: the
  `.sprj` must be found); the project is **re-read on every `convert`**; options
  `includeContactInfo` (default false, personal data) and `unmapped` (`description` | `drop`);
  `suggestedContext()` = the project record set IRI (we ignore it: the data source decides the
  graph).
- Emission: `startRDF`, namespaces (`rico`, `rdf`, `rdfs`, `xsd`, `skos`, `ric-rst`, `mydata`),
  statements, `endRDF`. Duplicates dropped, no blank nodes, typed dates.
- **IRIs** (`UriFactory`): `{base}/session/{dir}`, `{base}/person/{key}`, `{base}/language/…`,
  `{base}/project/{projectDirName}` and records under their container
  (`…/record/{fileName}`). They depend on the base IRI and on Lameta names (folders, person
  codes, file names), never on the import date: **re-importing with the same base IRI gives the
  same IRIs**, which keeps the data matching links valid. Renaming the lameta project folder
  changes only the project and folder record sets.
- Warnings (empty `.meta`, fallback project file, unresolved contributor, unmapped field…) are
  only **logged with SLF4J** (`LametaProjectReader`, `AgentRegistry`,
  `UnmappedFieldHandler`). Inside the backend they go to Spring's Logback.

### 2.3 FieldArchive today

- `app:sourceTool` is still read (`DataSourceDto.sourceTool`, "Source" column, filter, detail
  panel) but **nothing writes it** since commit fff0e3c, which also removed the form's select
  (`None`, `Tropy`, `Lameta`, `Gramps`, `Other`). Old projects may hold any of these, `""`, or
  nothing.
- RDF file imports already **stream** into the store: `Rio` parser → `RDFInserter` →
  connection (`FileImportService`), or `conn.add(InputStream, TURTLE, ctx)`
  (`DataSourceService.replaceGraphContent`). Both are **Turtle only**.
- Sync (`POST /datasources/{shortName}/sync`): one transaction (clear graph + load +
  `app:lastSync` + location), rollback on failure, then `ProjectDataChangedEvent`.
- Creation (`POST /datasources/external`) uploads the file *bytes*; a lameta project is a
  directory and cannot be uploaded, so plugin sources go **by path** (the backend runs on the
  user's machine and reads it directly).
- Electron ships the backend jar as an `extraResources` entry; `FIELD_ARCHIVE_DATA` =
  `app.getPath('userData')`; the preload bridge can pick a file, not a folder, and cannot open
  a folder in the OS.

## 3. Design

### 3.1 One abstraction: an importer emits statements into a handler

```
                  ┌──────────────── ImporterRegistry ────────────────┐
 "rdf-file" ──▶ RdfFileImporter      (built in: Rio parser on a file or an upload)
 "lameta"   ──▶ PluginImporter ──▶ RdfConverterProvider.create(path, options).convert(h)
                  └───────────────────────────┬──────────────────────┘
                                              ▼  RDFHandler h
             GraphReplacingSink: CountingHandler → ContextEnforcer(source graph) → RDFInserter(conn)
                                              ▼
                     RepositoryConnection, ONE transaction: clear(graph) + statements
                                + app:lastSync / location / importer / lastImport
                     commit → ProjectDataChangedEvent      (any exception → rollback)
```

- `Importer` (backend interface, `services/importer/`): `id()`, `label()`, `description()`,
  `options()`, `inputKind()`, and `emit(ImportInput, ImportSettings, RDFHandler)`.
  - `RdfFileImporter` (id `rdf-file`): `ImportInput` is a path **or** the uploaded stream
    (uploads keep working, still without any temporary file); the parser format comes from the
    file name (`Rio.getParserFormatForFileName`, Turtle as fallback, **Q8**).
  - `PluginImporter`: wraps one `RdfConverterProvider`; `emit` calls `create(path,
    ConverterOptions(baseIri, options))` then `convert(handler)`, with the thread context class
    loader set to the plugin's loader during the call.
- `GraphReplacingSink` (the only place that writes): an `RDFHandler` chain ending in
  `RDFInserter(conn)` with `enforceContext(sourceGraph)`, so whatever context a plugin or a TriG
  file puts on a statement, it lands in the source's graph. Counts statements. Namespaces:
  as today (`RDFInserter` adds a prefix to the store only if it is not defined yet).
- Memory: statements stream through; nothing is collected in a `Model` except for the preview
  (§4.1). The NativeStore buffers the uncommitted transaction itself, as for a file import today.
- `DataSourceService.synchronizeExternalDataSource` and the creation of an external source both
  call **one** method, `importIntoSource(shortName, importerId, input, settings)`, which owns
  the transaction (today's `replaceGraphContent`, generalised). `FileImportService.importTurtle`
  (used at creation) goes through it too.

### 3.2 Plugin discovery and loading

| Origin | Location |
|---|---|
| bundled | `fieldarchive.importers.bundled-dir` if set (Electron passes `<resourcesPath>/ricImporterPlugins`), else the `ricImporterPlugins` directory next to the running jar, or next to `target/classes` under `mvn spring-boot:run` (both are `Backend/target/ricImporterPlugins` in development) |
| user | `$FIELD_ARCHIVE_DATA/ricImporterPlugins`, created at startup if missing |

- `ImporterPluginScanner` (no Spring): every `*.jar` directly in a directory is one plugin;
  every **subdirectory** is one plugin made of all its jars (a plugin with extra libraries
  needs no shading). One `URLClassLoader` per plugin, parent = the backend's class loader
  (parent-first: RDF4J, SLF4J and the API come from the backend, so `RdfConverterProvider` is
  the same class on both sides). `ServiceLoader.load(RdfConverterProvider.class, loader)`,
  keeping the providers defined by that loader. Version from the jar's
  `META-INF/maven/*/*/pom.properties` (else `Implementation-Version`).
- Failures are caught **per plugin** and listed as problems (unreadable jar,
  `ServiceConfigurationError`, `LinkageError` = built against an incompatible API, exception
  in `name()`); startup never fails because of a plugin.
- Names: the id is the provider's `name()`, matched ignoring case; `rdf-file` is reserved.
  Same name in both directories: **Q3**. Same name twice in one directory: both refused.
- `ImporterRegistry` (`@Service`): an immutable snapshot (built-in importer, plugins,
  problems, directories), built at startup and by `rescan()` (**Q4**), which closes the
  previous loaders (on Windows a loaded jar stays locked until then).

### 3.3 Running a plugin import

1. Resolve the importer (request value, else stored value) → 409 "No plugin for this data
   source: <name>" if missing.
2. Check the path exists (a directory for lameta; `inputKind`, **Q7**).
3. Base IRI: the stored `app:importBaseIri`, else the default (**Q5**), stored at the first
   successful import so later imports mint the same IRIs.
4. `conn.begin()`; `clear(sourceGraph)`; `provider.create(...)` (validates the project);
   `convert(sink)`; write `app:lastSync`, `app:sourceLocation`, `app:sourceTool`,
   `app:importerOptions`, `app:importBaseIri`, `app:lastImport`; `commit()`;
   `ProjectDataChangedEvent` (the data matching marks the pairs as stale, the usage profile
   cache is dropped).
5. Any exception → `rollback()`: the source keeps its previous triples. Messages:
   `RdfConversionException` / plugin exception → 400 "Import with lameta failed for <path>:
   <message>"; `LinkageError` → 500 "The lameta plugin is not compatible with this version of
   FieldArchive".
6. **Warnings**: during the call, a Logback appender attached to the plugin's loggers and
   filtered on the importing thread collects WARN/ERROR events; they are returned with the
   result (and kept in `app:lastImport`), so the user sees "3 empty .meta files ignored" without
   any change to the plugin API (**Q9**).

### 3.4 Data model (metadata graph, `CTX_META`, so already in backups)

| Predicate | Value |
|---|---|
| `app:sourceTool` (kept) | importer id: a plugin name or `rdf-file` |
| `app:sourceLocation` (kept) | file **or directory** path |
| `app:importBaseIri` (new) | base IRI given to the plugin, fixed once (**Q5**) |
| `app:importerOptions` (new) | JSON object literal, only non-default values (**Q6**) |
| `app:lastImport` (new) | JSON literal: importer, plugin version, statements, date, warnings |

Reading old values (**Q2**): absent, `""`, `Other` → `rdf-file`; a plugin name ignoring case
→ that plugin (rewritten canonically on the next save); anything else (`Tropy`, `Gramps`, an
uninstalled plugin) → *missing importer*.

### 3.5 Build and packaging

- `Backend/pom.xml`: repository `github` (`https://maven.pkg.github.com/sylvainloiseau/*`);
  dependency `io.github.sylvainloiseau:ricimporterplugin` (compile); `maven-dependency-plugin`
  `copy` at `process-resources` (so `mvn spring-boot:run` has it too) of
  `io.github.sylvainloiseau:lameta2rico:jar` (plain jar, no transitive dependencies) into
  `${project.build.directory}/ricImporterPlugins`. Versions as properties.
- The directory is **next to** the Spring Boot jar, not inside it (a nested jar cannot be
  loaded by a plain `URLClassLoader`, and would not be a replaceable plugin).
- `package.json` `extraResources`: `Backend/target/ricImporterPlugins` → `ricImporterPlugins`;
  `electron/main.js` passes `--fieldarchive.importers.bundled-dir=…` to the backend.
- Every build machine needs a GitHub token with `read:packages` in `~/.m2/settings.xml`
  (GitHub Packages requires it even for public packages): documented in the README.

### 3.6 REST

| Endpoint | Content |
|---|---|
| `GET /importers` | `{ importers: [{ id, label, description, options[], inputKind, origin, version, jar }]` (the built-in `rdf-file` last), `problems: [{ jar, message }], directories: { bundled, user } }` — no open project needed |
| `POST /importers/rescan` | rescan, same payload (**Q4**) |
| `POST /datasources/external` | `dataSource` gains `importer`, `importerOptions`, `importBaseIri`; with a plugin: `sourceLocation` required, no file |
| `PUT /datasources/{shortName}` | same three fields |
| `POST /datasources/{shortName}/sync` | gains `importer`, `importerOptions`, `importBaseIri`; returns `ImportResult` (statements, warnings) |
| `DataSourceDto` | gains `importer` (resolved id, or the raw unknown value), `importerStatus` (`RDF_FILE` / `PLUGIN` / `MISSING`), `importerOptions`, `importBaseIri`, `lastImport` |

### 3.7 Frontend (gestion-sources)

- `ImporterHttpService`, `models/importer.model.ts`.
- Create and edit panels (**Q6** for create): "Import with" drop-down: the plugins by label,
  then "Sync with an RDF file" last.
- Stored value without plugin: drop-down disabled, showing the value; below it: *No plugin for
  this data source: "<name>". Put the plugin in your FieldArchive configuration directory or
  turn the value to raw RDF.* Buttons:
  - **Open the plugin directory**: new Electron bridge `openPath` (`shell.openPath`) on the
    user directory given by `/importers`; outside Electron the path is shown with a copy
    button. Then **Rescan** (**Q4**).
  - **Turn the value to raw RDF**: sets the drop-down to "Sync with an RDF file" in the form
    (saved by Update, used by Import).
  - Import disabled in this state.
- Path: with a directory plugin, "Choose folder…" (new bridge `selectDirectory`,
  `showOpenDialog({ properties: ['openDirectory'] })`); with `rdf-file`, today's file picker.
- Options block (collapsible "Importer options"): one field per `OptionDescriptor` (checkbox
  when the default is `true`/`false`, else text) and the base IRI.
- After an import: snackbar with the number of statements, and the warnings in an expandable
  list. "Source" column, filter and detail panel show the importer label (⚠ when missing) and
  the last import.
- Styled with the data-matching components, like the other restyled pages.

### 3.8 Tests

- `ImporterPluginScannerTest`: jars built in a `@TempDir` from small test providers (valid,
  duplicate, throwing, no provider, corrupt, subdirectory plugin), precedence, reserved name.
- `PluginImportTest` (in-memory store): the real lameta jar from `target/ricImporterPlugins`
  on a small fixture copied from `lameta2rico/src/test/resources/LametaProjects/Test`:
  statements only in the source graph; a second import gives the same IRIs and the same count;
  a failing conversion (missing `.sprj`) leaves the previous content; metadata and event
  written; warnings collected.
- `RdfFileImporter`: Turtle, RDF/XML, TriG (forced into the source graph), upload.
- Manual: Electron dev mode (folder picker, open directory, rescan) and a packaged build.

### 3.9 Documentation

`CLAUDE.md` (importers, directories, endpoints, predicates), `Doc/Manual.md` (importing a lameta
project, installing a plugin), `README.md` (GitHub token for the build).

## 4. Suggestions

1. **Preview before replacing** (`?dryRun=true`): the converter runs into an in-memory `Model`
   (still no file) and the diff with the current graph is shown: statements and entities by type
   added / removed, and the accepted matching links whose IRIs would disappear (they would
   become orphaned). Re-imports become safe to run.
2. **API additions** to `ricImporterPlugin` (backward-compatible default methods):
   `inputKind()` (FILE / DIRECTORY / ANY), `label()`, an import report / warnings callback
   (cleaner than capturing the plugin's log, §3.3 step 6), `apiVersion()`.
3. **Releases instead of SNAPSHOTs** before shipping, and later Maven Central (the
   `io.github.sylvainloiseau` groupId is Central-ready), removing the GitHub token requirement.
4. A **plugins panel** (loaded plugins, origin, version, problems, open directory, rescan).
5. Later: background import with progress and cancel; flag a source as out of date when its
   directory changed since the last import.

Security: a plugin is arbitrary code running with the user's rights; only the bundled jars and
those the user puts in their own directory are loaded.

## 5. Decisions and open questions

| | Decision |
|---|---|
| Q1 | Plugins are found by `fr.cnrs.lacito.ricimporterplugin.RdfConverterProvider`. |
| Q2 | Names matched ignoring case; absent / `""` / `Other` = "Sync with an RDF file"; `Tropy`, `Gramps` = missing plugin. |
| Q3 | Same name in both directories: the user directory wins. |
| Q4 | Rescan: `POST /importers/rescan` + a link next to "Open the plugin directory". |
| Q6 | Plugin options editable per data source; drop-down in the create panel too; a source whose first import fails is kept, empty. |
| Q7 | **API change**: `RdfConverterProvider.inputKind()` (§6), implemented in `lameta2rico` (DIRECTORY); FieldArchive shows the matching picker. |
| Q8 | "Sync with an RDF file" accepts every RDF format, chosen from the file extension. |
| Q10 | Coordinates `io.github.sylvainloiseau:lameta2rico`. |
| Q5 | Base IRI as proposed below: importer option prefilled with `<project prefix>/source/<shortName>`, editable, fixed after the first import. |
| Q9 | (a): the plugin's SLF4J warnings are captured during the import (§3.3 step 6); no API change. |

- **Q5 (decided)** — the *base IRI* is not the graph name. The graph is
  `urn:datasource:<shortName>` (the container, shown under "Short Name (Graph ID)"). The base
  IRI is the prefix of the **entities inside** the graph, which the plugin mints itself
  (`ConverterOptions.baseIri()`, the CLI's `-p`): with base `http://tuwari.huma-num.fr/corpus/tuwari`,
  person `KAL` becomes `http://tuwari.huma-num.fr/corpus/tuwari/person/KAL` and session `S1`
  `…/tuwari/session/S1`. The API makes it mandatory and FieldArchive must choose it. Proposal: a
  "Base IRI of the imported entities" field in the importer options, prefilled with
  `<project prefix>/source/<shortName>`, editable (a corpus URI such as the Huma-Num one is a
  good choice), and fixed after the first import (changing it renames every entity, which
  orphans the matching links; the field then warns). Two sources importing the same lameta
  project with the same base IRI produce identical IRIs, which the data matching treats as the
  same entity.

## 6. Changes to the plugin projects (Q7)

**Done (2026-10-17), uncommitted in both repositories, installed in `~/.m2`:**
- `ricImporterPlugin`: new `InputKind { FILE, DIRECTORY, FILE_OR_DIRECTORY }` (with
  `acceptsFile()` / `acceptsDirectory()`) and `default InputKind inputKind()` returning
  `FILE_OR_DIRECTORY` on `RdfConverterProvider`; `InputKindTest`; README (API table, "Writing a
  converter", and the stale `fr.cnrs.lacito:ricImporterPlugin` coordinates fixed). Version
  unchanged (0.1.0-SNAPSHOT).
- `lameta2rico`: `Lameta2RicoConverterProvider.inputKind()` → `DIRECTORY`; asserted in
  `providerIsDiscoveredThroughServiceLoader`; README.
- Order: API `mvn install`, `lameta2rico` `mvn install`, FieldArchive. Publishing both to
  GitHub Packages (`mvn deploy`) is left to you (or done on your go-ahead), since it publishes
  outside this machine.
- A plugin built against the old API still loads: the interface comes from the backend's
  class loader, so it gets the default `FILE_OR_DIRECTORY` (both pickers are shown).
