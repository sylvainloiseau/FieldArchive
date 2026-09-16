# FieldArchive — An application for the description of linguistic fieldwork data

Manage and describe linguistic fieldwork archive using RDF

## Features

- Create and manage projects
- Define one or more ExternalDataSource to your project: an ExternalDataSource is an RDF files exported from another app, whose data will not be editable in the FieldArchive application and that you can import again, erasing the previous import.
- perform record linkage between the various data source, allowing to aggregated description of place, person, event, document, realia comming from various annotation tools
- Create and edit entities (person, place, document, event...) in the app
- Perform query on the data

## Installation

Download the latest release from the [GitHub releases page](https://github.com/sylvainloiseau/fieldArchive).

## User manual

See Doc/Manual.md

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

The backend exposes the following endpoints on `http://localhost:8080/`:

#### Projects

| Method | URL | Description |
|---------|-----|-------------|
| GET | /projects | List existing projects |
| POST | /projects/open | Open or create a project |
| GET | /projects/current | Get the currently active project |
| POST | /projects/close | Close the active project |
| ...  | ... | ... |

#### Data Sources

| Method | URL | Description |
|---------|-----|-------------|
| GET | /datasources | List the sources |
| POST | /datasources/internal | Create an internal source |
| POST | /datasources/external | Create an external source |
| PUT | /datasources/{name} | Modify a source |
| DELETE | /datasources/{name} | Delete a source |
| POST | /datasources/{name}/sync | Sync an external source |

#### SPARQL

| Méthode | URL | Description |
|---------|-----|-------------|
| POST | /sparql/select | Executer une requête SELECT |
| POST | /sparql/update | Executer une requête UPDATE |

## Authors

- Mohamed Saber Mahjoub (Main developer and computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Khaoula Charef (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Mehrez Bey (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Noha Aqaoui (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Vitor Tomas Rodrigues Jordã (computer science Student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Sylvain Loiseau
