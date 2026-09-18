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
