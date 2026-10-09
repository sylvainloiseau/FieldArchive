# FieldArchive — An application for the description of linguistic fieldwork data

Linguistic fieldwork data consist of a variety of objects: texts, maps, recordings, stimulus sets, still images, specimens of various kinds of realia, information about people, places, events. This data is a dense web of relations: between people and the data sessions they participate in, or the recordings or photos they are featured in; between an event and the documents and recordings it produces; between an event and photos and places; etc.

Linguists have few solutions for the description of this data, that would make them 1/ more searchable and discoverable on one hand, and 2/ more archivable for future use and future research questions on the other hand, whereas this contextualization of the linguistic material proper could turn out, in the future, to be the only remaining data about the language and be precious for authorizing new kinds of research, as stressed by the documentary linguistic perspective.

This application tries to address these needs. It has the following features:

- *It does not try to replace existing apps when they exist for specific types of data.* Many useful applications exist for the annotation of specific types of data (photographs, biographical data, etc.). They can obviously not be replaced. Instead, the FieldArchive app lets you define "External sources" for data regularly re-imported from such apps, and aggregates that data with data created directly in the app. Data coming from external sources cannot be modified or deleted in the application; instead, they can be edited in the source application and imported again (unless one decides to import them permanently).
- *FieldArchive uses RDF as the data model for the representation of data.* RDF allows you to link data easily and to use various ontologies according to the user's needs.
- *FieldArchive facilitates the use of an archiving ontology, RiC-O [Records in Contexts](https://www.ica.org/standards/RiC/RiC-O_1-1.html)*. RiC-O is defined for archival use and helps to describe the data in a way suitable for archivists. However, the user can refer to any other ontology to describe the data, such as PNV [Person Name Vocabulary](https://www.lodewijkpetram.nl/vocab/pnv/doc/) for complex naming systems, [Bio](https://vocab.org/bio/) for biographical data, etc.
- *FieldArchive offers an algorithm for data deduplication (or record linkage)*: identical entities can be referred to using different IDs in different data sources. The algorithm identifies IDs likely to refer to the same real-world entity in two data sources, and allows users to confirm or reject such linkage. Once linked, the application will smoothly aggregate data from different sources.

## Configuration and data directory

An application configuration directory is created at `$APP_CONFIG_DIR/FieldArchive`, where $APP_CONFIG_DIR is the directory for application configuration files in your system (typically `~/.config/` on Unix-like systems, `~/Library/Application\ Support/` on macOS).

## Main pages

### Project management page

The home page provides access to the project management page, where you can create, list and open projects. Only one project can be active at a time.

You can export a project into a zipped archive in order to back it up, share it with others, or reinstall it on a different machine.

### Data sources management

Re-importing ("syncing") data annotated in a third-party app is a core feature: the user can export data from a third-party tool (such as [Tropy](https://tropy.org), [Lameta](https://www.lameta.org/home), [Gramps](https://gramps-project.org)) and re-import it into FieldArchive when necessary, replacing the previously imported data.

Two different types of sources are supported:

- **Internal data source**: annotations that are entered directly into the application and can be modified
- **External data source**: annotations that are imported from a file produced by a third-party tool. These sources are read-only in the application and can be re-imported at any time

A data source corresponds to a named graph in the RDF4J triplestore.

On the data source management page, you can create a new External data source, give it a name and specify the location of the file to import. After modifying the data in the corresponding application, you can export it as an RDF file and re-import it into FieldArchive. Previous data from that External data source will be replaced by the newly imported data.

In the application, all triples coming from the various sources are shown together. For instance, all triples describing the same entity are shown on the entity editing page. However, the origin (the data source it comes from) of each triple is indicated with a badge.

### Data matching

The same person, place or document is often described in several data sources under different IRIs: a speaker recorded in Gramps and photographed in Tropy, for instance. The **Data matching** page (button on the data sources page) finds these entities, lets you confirm or reject each proposal, and remembers your decisions.

- **Source pairs** (left): every pair of data sources, with the number of proposals to review (▲), confirmed links (✓), inferred links (≡) and rejections (✗). ⟳ means the proposals are out of date: a source was re-synced, the settings changed, or links were undone since the last run.
- **Run matching** compares the entities of the two sources and proposes the pairs whose score is high enough. Each proposal can be expanded to see *why*: for each compared property, the two values and their similarity.
- **Accept** (✓, key `a`) or **reject** (✗, key `r`) a proposal; **undo** (↺, key `u`) a decision. Use `j`/`k` or the arrows to move through the list, the space bar to show the details, `c` to compare the two entities side by side, `m` to link an entity by hand. *Accept all ≥ 95 %* accepts the most certain proposals at once.
- **Manual link** links two entities the algorithm did not propose.
- A rejected pair is never proposed again. An entity linked to an entity of the other source is not proposed again for that pair of sources.
- **Inferred links**: if A (source 1) is the same as B (source 2), and B is the same as C (source 3), then A and C are linked automatically. To break an inferred link, undo one of the links it comes from. The application refuses links that would contradict your decisions: two different entities of the same source can never be the same entity, and two entities you rejected can never end up linked through other sources.
- Confirmed and inferred links appear as **Same as** on the entity editing page, and as `owl:sameAs` triples in a dedicated graph that SPARQL queries (and the natural-language agents) can use: `<http://cnrs.lacito/app#/context/matching>`.
- Your decisions are stored in the project folder (`data_matching.json`) and included in the project backup.

**Matching settings** (button on the data sources page, or ⚙ on the matching page) decide what is compared:

- *Type groups* gather equivalent classes from different vocabularies (e.g. `foaf:Person` and `rico:Person`). Only entities of the same group are compared; subclasses are included.
- In each group, *concepts* list the properties expressing the same information (e.g. `foaf:name`, `rico:name`), how to compare them (text, name, date, exact value…), and their weight in the score. A *required* concept must be comparable for a pair to be proposed; the *minimum evidence* prevents a match on a single minor property.
- The *Coverage* column shows, per source, how many entities have a value for each concept; the list of *predicates used by these types* suggests properties not compared yet.
- **Preview** runs the settings being edited on a pair of sources without saving anything, and shows how the proposals would change and how many of your past decisions the settings would reproduce.
- The settings are saved in the project folder (`matching-configuration.json`); *Reset to default* comes back to the bundled settings. They can also be edited as JSON, exported and imported.

### Resources page

The Resources page allows you to visualize, filter, edit and navigate through the RDF entities of the active project.

Entities can have one or more RDF types: `rico:Person` (in the RiC-O ontology), `foaf:Person` (in the FOAF ontology), maximum one per ontology.

The **Type navigator**, on the left, allows you to see the types grouped by ontology. When you click on a type, you can see the entities that have that type in the table on the right.

For each ontology, the Type navigator first shows the main types for that ontology — the list of main types is configurable. It then shows all the types actually used in the project. Finally it lists "taxonomic" types, i.e. types that are intended for creating taxonomies (e.g. `rico:EventType` is for creating entities describing event types (data session, spontaneous conversation...) that you will reuse for the description of an actual Event).

When you click on an entity in the table on the right, you can open the **Edit entity page**. This page allows you to see and edit the entity's RDF types, as well as its properties.

The first line in the form shows the entity type. You can declare one type for each of the declared ontologies. Below there is one tab per declared ontology, showing the entity's properties in that ontology and allowing you to create/edit them.

You can create a new property in an ontology tab only if the entity has a type declared for that ontology.

In RDF, each property has a given type of object (= value), declared in the corresponding ontology. For example, the property `rico:date` is defined as having a string value in the RiC-O ontology. The property `rico:hasOrHadDescendant` expects an IRI (= the ID) of a `rico:Person`. The application takes care of this: when you create a property defined with a string as object, a text field is offered. When you create a property defined with a `rico:Person` as object, a dropdown list is offered with the existing `rico:Person` entities in your project — or a button "Create" for creating a new `rico:Person` entity on the fly.

Moreover, properties can have multiple values (if the ontology does not restrict the cardinality explicitly). Several values can then be associated with a property in the application.

### SPARQL page

The backend exposes a SPARQL endpoint which lets you query and modify the data in the triplestore via SELECT and UPDATE queries. The frontend allows you to enter any SPARQL query and see the results.

When an UPDATE creates or modifies entities in the project's own data, the application sets their creation and modification dates itself, as it does for edits made in the entity editor.

## Asking questions in natural language

The field in the middle of the header ("Ask about this project…", shortcut Ctrl+K / ⌘K) lets you ask a question or give an instruction in plain French or English: *list all persons*, *which recordings were made in 2019?*, *rename Marie Dupond to Marie Durand*, *add the gender "masculine" to Jean Dupond*…

Press Enter: a window opens with the question and a first proposal, written as a SPARQL query, with a short explanation. **Nothing is run until you click** *Run query* (for a question) or *Apply changes* (for a modification).

- A question opens the SPARQL page with the query and its results.
- A modification is applied, then the entity it created or changed is opened in the entity editor.
- *Open in SPARQL editor* lets you check or correct the query before running it. A corrected query is remembered as a correction (see below).

Only the project's own data (the internal data source) can be modified this way; external sources stay read-only.

### Choosing an agent

The drop-down at the top of the window chooses who translates your question:

| Agent | Cost | What it needs | Notes |
|---|---|---|---|
| **Claude (Anthropic)** | paid, a few cents per question | an Anthropic API key (console.anthropic.com) | best results |
| **ChatGPT (OpenAI)** | paid | an OpenAI API key (platform.openai.com) | a ChatGPT Plus/Pro subscription is **not** an API key |
| **Local model (Ollama)** | free | [Ollama](https://ollama.com) installed, and a model downloaded with `ollama pull gpt-oss:20b` (about 16 GB of memory) | nothing leaves your computer; slower (tens of seconds to minutes), less reliable |
| **Built-in (no AI)** | free | nothing | understands only a fixed set of phrasings (click *What can I ask?*) |

API keys can be typed in the window (*Set API key*); they are checked, then saved in `settings/llm-keys.json` in the application's data directory, never in a project or a backup. Environment variables `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`, when set, take precedence.

**What is sent to Claude or ChatGPT**: your question, a description of the RiC-O ontology, statistics on how your project's data is encoded (counts and recurring short values, never names or notes), one sample entity per main type, past questions, and the records the agent looks up to answer (entity names, properties, notes). With the local model or the built-in agent, nothing leaves your computer.

### When the agent asks you back

If a name matches several entities (or none), the agent asks *which one?* and lists the candidates. If the project has no established way of recording what you ask (for example a gender), it proposes the possible encodings — the one used in your data, the one in the configuration, the one defined by RiC-O — and asks you to choose. Your choice is remembered for the project, so the question is asked only once.

### The history of questions

Every question whose query was actually run is recorded in `natural_language_query_history.json` in the project directory (it is included in backups). The agents use the most similar past questions as examples, so they reuse the encodings your project has settled on; a query you corrected in the SPARQL editor counts as a correction and takes precedence. The history is shared by all agents: the built-in agent can even reuse a question first answered by Claude or ChatGPT, with new names. Open *History of this project's questions* in the window to reuse a question or delete an example that taught the agent something wrong.
