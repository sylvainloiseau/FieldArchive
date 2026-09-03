# FieldArchive — An application for the description of linguistic fieldwork data

Linguistic fieldwork data are made of a variety of objects: texts, maps, recordings, stimulus set, still images, references to people, places, events, specimen of various types. This data is a dense web of relations: between people and texts, recordings or photo; betwen people, place and event, etc. Linguists have little solution for the description of this data, that would make them more searchable / discoverable on one hand, and more archivable for futur use of the documentation.

This application try to address these needs. It has with the following features:

- Many usefull applications exist for the annotation of specific type of data (photo, biography, etc.). They can obviously not be replaced. The FieldArchive app offers to define "External sources" for data imported from such apps and to aggregate data from various such sources, together with data created directly in the app. Data coming from external sources cannot be modified or deleted in the application ; instead, they can be edited in the source application and imported again (unless one decide to import them definitely).
- Linguistic field archive offers an algorithm for data deduplication (or record linkage): identical entities can be refered to using different IDs in different data sources. The algorithm identifies IDs likely to refer to the same real-world entity in two data sources, and offers to the user the choice to confirm or infirm such linkage. Once linked, the application will smoothly aggregate data from different sources.
- Linguistic field archive use RDF as the data model for the representation of data. RFD allows to link data easily and to use various ontologies according to the need of the user.
- Linguistic field archive use the RICO [Records in context](https://www.ica.org/standards/RiC/RiC-O_1-1.html) has the base ontology to represent data. RICO is defined for archivistic use and helps to describe the data in an archivist-sensible way. However, the user can refers to any new ontology to describe the data, such as PNV [Person Name Vocabulary](https://www.lodewijkpetram.nl/vocab/pnv/doc/) for complexe naming system, [Bio](https://vocab.org/bio/) for biographical data, etc.

## Installation

Download the latest release from the [GitHub releases page](https://github.com/sylvainloiseau/fieldArchive).

## User manual

### Configuration and data directory

An application configuration directory is created at `~/$APP_CONFIG_DIR/fieldArchive`, where $APP_CONFIG_DIR is the directory for application configuration files in your system (typically `~/.config/` on Unix-like systems, `~/Library/Application\ Support/` on mac).

### Project management page

The home page provides access to the project management page, where you can create, list and open projects. Only one project can be active at a time. Each project has its own isolated RDF4J triplestore.

You can export a project to an archive in order to backup, share it with others, or re-install it on a different machine.

### Data sources management

A key feature of FieldArchive is the management of data sources. Two different types of sources are supported:

- **Internal data source** : RDF triples that are entered directly into the application and can be modified
- **External data source** : RDF triples that are imported from a file produced by a third-party tool (Tropy, Lameta, Gramps). These sources are read-only in the application and can be re-imported at any time

A data source corresponds to a named graph in the RDF4J triplestore.

On the data source management page, you can create new External data sources, give it a name and give a location for the file to be imported. After modification in the corresponding app, export of the data from the app in a RDF file, you can re-import it into FieldArchive. Previous data from that External data source will be replaced by the new ones.

In the application, all triples coming from the various sources are shown together. For instance, all triples describing the same entity are shown on the entity edition page. However, the origin (the data source) of each triple is indicated.

### Resources page

The resource page allows you to visualize, filter, edit and navigate through the RDF entities of the active project.

Entities can have one or more RDF types: `rico:Person`, `rico:Event` (in the RICO ontology), `foaf:Person` (in the FOAF ontology), etc. The **Type navigator**, on the left, allows you to see the types grouped by ontology. When you click on a type, you can see the entities that have that type in the table on the right.

For each ontology, the Type navigator shows first the main type for that ontology -- the list of main type is configurable. It then show all the types actually used in the project. Finally it lists "taxonomic" types, i.e. types that are intended for creating taxonomies (e.g. the rico:EventType is for creating entities describing event type (DataSession, Spontaenous conversation...) that you will reuse for the description of an actual Event.).

When you click on an entity in the table on the right, you can open the **Edit entity page**. This page allows you to see and edit the entity's RDF types, as well as its properties.

The first line in the form show the entity type. You can declare one type for each of the declared ontologies. Below there is one tab by declared ontology, showing the entity's properties in that ontology and allowing you to create/edit them.

You can create new property in a tab-ontology only if the the entity has a type declared for that ontology.

In RDF, each property has a given type of object (= value), declared in the corresponding ontology. For example, the property `rico:date` is defined as receiving a string in the RICO ontology. The property `rico:hasOrHadDescendant` is defined as receiving the IRI (= the ID) of a `RICO:Person`. The application takes care of this: when you create a property defined with a string as object, a text field is offered. When you create a property defined with a `rico:Person` as object, a dropdown list is offered with the existing `rico:Person` entities in your project -- or a button "Create" for creating a new `rico:Person` entity on the fly.

Moreover, properties can have multiple values (if the ontology does not restrict the cardinality explicitly). Several values can the be associated with a property in the application.

### SPARQL page

The backend expose an endpoint SPARQL which allows to query and modify the data in the triplestore via SELECT and UPDATE queries. The Frontend allows you to enter any SPARQL query and see the results.

## Running the application from sources

### Requirements

Running the project from sources requires:

- Java 17 or later
- Node.js 18 or later and npm
- Angular CLI : `npm install -g @angular/cli`
- Maven 3.8 or later

### Clone the projet

```bash
git clone https://github.com/sylvainloiseau/fieldArchive
cd fieldArchive
```

### Run the Spring Boot backend

Open a terminal in the `Backend` directory:

```bash
cd Backend
mvn spring-boot:run
```

Windows :

```bash
cd RDF_Back
mvnw.cmd spring-boot:run
```

The Backend start on `http://localhost:8080`. Wait for the message:

```
Started RdfBackApplication in X seconds
```

### Run the Angular frontend in a browser

Open a terminal in the `Frontend` directory:

```bash
cd Frontend
npm install
ng serve
```

The application is accessible on your browser at `http://localhost:4200`.

### Launch the Electron desktop app

#### 

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
npm run build:frontend   # compile Angular dans electron/dist/frontend
npm run build:backend    # compile Spring Boot en JAR
```

Then launch:

```bash
npx electron .
```

In production mode, Electron loads the compiled Angular static files and automatically launches the Spring Boot backend in the background.

## Contributing

The following pieces of information may help you look at the code base.

### Architecture

The application follows a three-layer architecture:

- **Frontend** : an Angular application that runs in the browser or Electron
- **Backend** : a Spring Boot REST API that exposes data and manages business logic
- **Stockage** : a RDF4J NativeStore triplestore that persists RDF data on disk

The communication between the frontend and backend is done via HTTP REST on port 8080. The frontend runs on port 4200 in development.

### Technologies

| Component         | Technology                                |
|-------------------|--------------------------------------------|
| Frontend          | Angular 19, Angular Material, Tailwind CSS |
| Backend           | Spring Boot 4, Java 17, Maven              |
| Stockage RDF      | RDF4J 5.2 NativeStore                      |
| Desktop           | Electron 29                                |
| Format de données | RDF, Turtle (.ttl), SPARQL                 |

### Project Structure

```
Projet-RDF/
├── Frontend/                        # Angular
│   └── src/app/
│       ├── components/
│       │   ├── gestion-projets/     # Project management
│       │   ├── gestion-sources/     # Data source management
│       │   |── gestion-ressources/  # Entity RDF management
│       │   └── 
│       ├── services/
│       │   ├── project.service.ts    # Project API calls
│       │   └── data-source.service.ts # Data source API calls
│       └── models/
│           └── data-source.model.ts
├── Backend/                         # Backend Spring Boot
│   └── src/main/java/com/uspn/rdf_back/
│       ├── controllers/             # Endpoints REST
│       ├── services/                # Business logic
│       ├── dtos/                    # Data transfer objects
│       └── config/                  # RDF4J and CORS configuration
├── electron/                        # Electron layer
│   ├── main.js                      # Electron main process
│   └── preload.js                   # Electron preload script (security)
└── package.json                     # Electron configuration
```

### Generate a standalone executable for your plateform (windows, linux, macos)

```bash
npm run dist
```

This will generate a standalone runnable for your platform (windows, linux, macos) in the `dist-electron/` directory.

### API REST

The backend exposes the following endpoints on `http://localhost:8080/api` :

#### Projets

| Méthode | URL | Description |
|---------|-----|-------------|
| GET | /api/projects | List existing projects |
| POST | /api/projects/open | Open or create a project |
| GET | /api/projects/current | Get the currently active project |
| POST | /api/projects/close | Close the active project |
| ...  | ... | ... |

#### Data Sources

| Méthode | URL | Description |
|---------|-----|-------------|
| GET | /api/datasources | Lister toutes les sources |
| POST | /api/datasources/internal | Créer une source interne |
| POST | /api/datasources/external | Créer une source externe |
| PUT | /api/datasources/{name} | Modifier une source |
| DELETE | /api/datasources/{name} | Supprimer une source |
| POST | /api/datasources/{name}/sync | Ré-importer une source externe |

#### SPARQL

| Méthode | URL | Description |
|---------|-----|-------------|
| POST | /api/sparql/select | Executer une requête SELECT |
| POST | /api/sparql/update | Executer une requête UPDATE |

# Authors

- Mohamed Saber Mahjoub (Main developper, Computer Science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Khaoula Charef (Computer Science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Mehrez Bey (Computer Science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Noha Aqaoui (Computer Science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Vitor Tomas Rodrigues Jordã (Computer Science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Sylvain Loiseau
