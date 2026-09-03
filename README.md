# FieldArchive — An application for the description of linguistic fieldwork data

Linguistic fieldwork data consist of a variety of objects: texts, maps, recordings, stimulus sets, still images, specimens of various kinds of realia, information about people, places, events. This data is a dense web of relations: between people and texts, recordings or photos; between people, places and events, etc. Linguists have few solutions for the description of this data, that would make them 1/ more searchable / discoverable on one hand, and 2/ more archivable for future use and future research questions on the other hand, when this contextualisation of linguistic material could turn out to be precious, and while no other data might be available on the speech community, as stressed by the documentary linguistic perspective.

This application tries to address these needs. It has the following features:

- *It does not try to replace existing apps when they exist for specific types of data.* Many useful applications exist for the annotation of specific types of data (photographs, biographical data, etc.). They can obviously not be replaced. Instead, the FieldArchive app offers to define "External sources" for data regularly re-imported from such apps and aggregate them with data created directly in the app. Data coming from external sources cannot be modified or deleted in the application; instead, they can be edited in the source application and imported again (unless one decides to import them permanently).
- *The Linguistic field archive app uses RDF as the data model for the representation of data.* RDF allows to link data easily and to use various ontologies according to the user's needs.
- *The Linguistic field archive app facilitates the use of an archiving ontology, RICO [Records in context](https://www.ica.org/standards/RiC/RiC-O_1-1.html)*. RICO is defined for archival use and helps to describe the data in a way suitable for archivists. However, the user can refer to any other ontology to describe the data, such as PNV [Person Name Vocabulary](https://www.lodewijkpetram.nl/vocab/pnv/doc/) for complex naming systems, [Bio](https://vocab.org/bio/) for biographical data, etc.
- *FieldArchive offers an algorithm for data deduplication (or record linkage)*: identical entities can be referred to using different IDs in different data sources. The algorithm identifies IDs likely to refer to the same real-world entity in two data sources, and allows users to confirm or reject such linkage. Once linked, the application will smoothly aggregate data from different sources.

## Installation

Download the latest release from the [GitHub releases page](https://github.com/sylvainloiseau/fieldArchive).

## User manual

### Configuration and data directory

An application configuration directory is created at `~/$APP_CONFIG_DIR/fieldArchive`, where $APP_CONFIG_DIR is the directory for application configuration files in your system (typically `~/.config/` on Unix-like systems, `~/Library/Application\ Support/` on macOS).

### Project management page

The home page provides access to the project management page, where you can create, list and open projects. Only one project can be active at a time. Each project has its own isolated RDF4J triplestore.

You can export a project to an archive in order to back up, share with others, or reinstall it on a different machine.

### Data sources management

A key feature of FieldArchive is the management of data sources. Two different types of sources are supported:

- **Internal data source**: RDF triples that are entered directly into the application and can be modified
- **External data source**: RDF triples that are imported from a file produced by a third-party tool (Tropy, Lameta, Gramps). These sources are read-only in the application and can be re-imported at any time

A data source corresponds to a named graph in the RDF4J triplestore.

On the data source management page, you can create new External data sources, give it a name and specify the location of the file to import. After modifying the data in the corresponding application, export it as an RDF file, and re-import it into FieldArchive. Previous data from that External data source will be replaced by the new ones.

In the application, all triples coming from the various sources are shown together. For instance, all triples describing the same entity are shown on the entity editing page. However, the origin (the data source) of each triple is indicated.

### Resources page

The resource page allows you to visualize, filter, edit and navigate through the RDF entities of the active project.

Entities can have one or more RDF types: `rico:Person`, `rico:Event` (in the RICO ontology), `foaf:Person` (in the FOAF ontology), etc. The **Type navigator**, on the left, allows you to see the types grouped by ontology. When you click on a type, you can see the entities that have that type in the table on the right.

For each ontology, the Type navigator shows first the main types for that ontology — the list of main types is configurable. It then shows all the types actually used in the project. Finally it lists "taxonomic" types, i.e. types that are intended for creating taxonomies (e.g. the rico:EventType is for creating entities describing event types (DataSession, Spontaneous conversation...) that you will reuse for the description of an actual Event).

When you click on an entity in the table on the right, you can open the **Edit entity page**. This page allows you to see and edit the entity's RDF types, as well as its properties.

The first line in the form shows the entity type. You can declare one type for each of the declared ontologies. Below there is one tab per declared ontology, showing the entity's properties in that ontology and allowing you to create/edit them.

You can create a new property in a ontology tab only if the entity has a type declared for that ontology.

In RDF, each property has a given type of object (= value), declared in the corresponding ontology. For example, the property `rico:date` is defined as having a string value in the RICO ontology. The property `rico:hasOrHadDescendant` expects an IRI (= the ID) of a `rico:Person`. The application takes care of this: when you create a property defined with a string as object, a text field is offered. When you create a property defined with a `rico:Person` as object, a dropdown list is offered with the existing `rico:Person` entities in your project — or a button "Create" for creating a new `rico:Person` entity on the fly.

Moreover, properties can have multiple values (if the ontology does not restrict the cardinality explicitly). Several values can then be associated with a property in the application.

### SPARQL page

The backend exposes an SPARQL endpoint which allows to query and modify the data in the triplestore via SELECT and UPDATE queries. The frontend allows you to enter any SPARQL query and see the results.

## Running the application from sources

### Requirements

Running the project from sources requires:

- Java 17 or later
- Node.js 18 or later and npm
- Angular CLI: `npm install -g @angular/cli`
- Maven 3.8 or later

### Clone the project

```bash
git clone https://github.com/sylvainloiseau/fieldArchive
cd fieldArchive
```

### Run the application in a browser from the sources

#### Run the Spring Boot backend

Open a terminal in the `Backend` directory:

```bash
cd Backend
mvn spring-boot:run
```

Windows:

```bash
cd Backend
mvnw.cmd spring-boot:run
```

The Backend starts on `http://localhost:8080`. Wait for the message:

```
Started RdfBackApplication in X seconds
```

#### Run the Angular frontend in a browser

Open a terminal in the `Frontend` directory:

```bash
cd Frontend
npm install
ng serve
```

The application is accessible in the browser at `http://localhost:4200`.

### Launch the Electron desktop app from the sources

#### Test

At project root:

```bash
cd Frontend
npm install
ng serve
```

On a second terminal:

```bash
cd ../
npm install
npx electron .
```

#### Development mode

Follow the steps "Run the Spring Boot backend" and "Run the Angular frontend in a browser" above and then:

```powershell
$env:NODE_ENV="development"
npx electron .
```

A window opens and loads the Angular application from `localhost:4200`.

#### Production mode

Compile the frontend and backend first:

```bash
npm run build:frontend   # Compile angular in electron/dist/frontend
npm run build:backend    # Compile spring boot in backend/target/
```

Then launch:

```bash
npx electron .
```

In production mode, Electron loads the compiled Angular static files and automatically launches the Spring Boot backend in the background.

### Generate a standalone executable for your platform (Windows, Linux, macOS)

```bash
npm run dist
```

This will generate a standalone executable for your platform (Windows, Linux, macOS) in the `dist-electron/` directory.

## Contributing

The following information may help you explore the codebase.

### Architecture

The application follows a three-layer architecture:

- **Frontend**: an Angular application that runs in the browser or Electron
- **Backend**: a Spring Boot REST API that exposes data and manages business logic
- **Persistence**: an RDF4J NativeStore triplestore that persists RDF data on disk

The communication between the frontend and backend is done via HTTP REST on port 8080. The frontend runs on port 4200 in development.

### API REST

The backend exposes the following endpoints on `http://localhost:8080/api`:

#### Projects

| Method | URL | Description |
|---------|-----|-------------|
| GET | /api/projects | List existing projects |
| POST | /api/projects/open | Open or create a project |
| GET | /api/projects/current | Get the currently active project |
| POST | /api/projects/close | Close the active project |
| ...  | ... | ... |

#### Data Sources

| Method | URL | Description |
|---------|-----|-------------|
| GET | /api/datasources | List the sources |
| POST | /api/datasources/internal | Create an internal source |
| POST | /api/datasources/external | Create an external source |
| PUT | /api/datasources/{name} | Modify a source |
| DELETE | /api/datasources/{name} | Delete a source |
| POST | /api/datasources/{name}/sync | Sync an external source |

#### SPARQL

| Méthode | URL | Description |
|---------|-----|-------------|
| POST | /api/sparql/select | Executer une requête SELECT |
| POST | /api/sparql/update | Executer une requête UPDATE |

## Authors

- Mohamed Saber Mahjoub (Main developer and computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Khaoula Charef (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Mehrez Bey (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Noha Aqaoui (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Vitor Tomas Rodrigues Jordã (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Sylvain Loiseau
