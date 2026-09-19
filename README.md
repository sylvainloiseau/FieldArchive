# FieldArchive — An application for the description of linguistic fieldwork data

Manage and describe linguistic fieldwork archives using RDF.

## Features

- Create and manage projects.
- Define optional *ExternalDataSources* for your project: an ExternalDataSource is an RDF file exported from another application (a photo database manager, etc.) that you can import into FieldArchive. Such a dataset is not editable within FieldArchive, but you can replace it with a fresh import (syncing) so that it reflects the changes you have made in the third-party application.
- Perform *record linkage* (matching, deduplication) between corresponding entities in different ExternalDataSources, producing an aggregated description of the places, persons, events, documents and realia coming from various annotation tools.
- Extend the application with additional RDF ontologies (for complex biographical information, detailed proper-name descriptions, etc.): the ontologies are loaded by the application at startup, and the types and properties they define become available in every editing form.
- Create and edit entities (person, place, document, event, etc.) within the app.
- Query the data with SPARQL.

## Installation

Download the latest release from the [GitHub releases page](https://github.com/sylvainloiseau/FieldArchive/releases) for your OS.

### Prerequisite

A Java runtime environment (JRE or JDK), version 17 or later, available on your `PATH`.

### First launch on macOS

The macOS application is **not notarized by Apple**, because notarization requires a paid
Developer ID. As a consequence, the first time you open the downloaded application, macOS
displays a warning of the form:

> *Apple could not verify "FieldArchive" is free of malware…*

This is expected and does not mean the application is broken. Downloading a file sets the
`com.apple.quarantine` attribute on it, and macOS refuses to run a quarantined application
that carries no notarization ticket. You only have to lift that restriction **once**; every
later launch works normally. Choose either of the two routes below.

#### Route 1 — System Settings (works on all recent macOS, including 15 Sequoia)

1. Open the downloaded `.dmg` and drag **FieldArchive.app** into your `/Applications` folder.
2. Double-click **FieldArchive** in `/Applications`. The warning above appears: dismiss it
   with **Done**.
3. Open **System Settings → Privacy & Security** and scroll to the bottom of the
   **Security** section, where a notice reads *"FieldArchive" was blocked…*.
4. Click **Open Anyway** and confirm with Touch ID or your password.
5. On macOS 15 (Sequoia) you may need to double-click the application a second time and
   confirm once more. After that, FieldArchive opens directly.

#### Route 2 — Terminal (a single command)

After copying **FieldArchive.app** to `/Applications`, run:

```bash
xattr -dr com.apple.quarantine /Applications/FieldArchive.app
```

This removes the quarantine attribute, so the notarization check no longer applies and the
application opens on a normal double-click.

> **Note:** up to macOS 14, right-clicking the application and choosing **Open**
> was enough to bypass this warning. Apple removed that Control-click override in macOS 15
> (Sequoia), so instructions you may find elsewhere telling you to right-click → Open no
> longer work — use one of the two routes above instead.

## User manual

See [Doc/Manual.md](Doc/Manual.md).

## Running the application from sources

### Requirements

Running the project from sources requires:

- Java 17 or later
- Node.js 18 or later and npm
- Angular CLI: `npm install -g @angular/cli`
- Maven 3.8 or later

### Clone the project

```bash
git clone https://github.com/sylvainloiseau/FieldArchive
cd FieldArchive
```

### Run the application in a browser from the sources

#### Run the Spring Boot backend

Open a terminal in the `Backend` directory:

```bash
cd Backend
mvn spring-boot:run
```

On Windows:

```bash
cd Backend
mvnw.cmd spring-boot:run
```

The backend starts on `http://localhost:8080`. Wait for the following message:

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

#### Quick test

From the project root:

```bash
cd Frontend
npm install
ng serve
```

In a second terminal:

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
npm run build:frontend   # Build the Angular app into electron/dist/frontend
npm run build:backend    # Build the Spring Boot backend into Backend/target/
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

This generates a standalone executable for your platform in the `dist-electron/` directory.

## Contributing

The following information may help you explore the codebase.

### Architecture

The application follows a three-layer architecture:

- **Frontend**: an Angular application that runs in the browser or Electron
- **Backend**: a Spring Boot REST API that exposes data and manages business logic
- **Persistence**: an RDF4J NativeStore triplestore that persists RDF data on disk

The frontend and the backend communicate over HTTP REST on port 8080. In development, the frontend runs on port 4200.

### Create a release on GitHub

A GitHub workflow (`.github/workflows/release.yml`) automatically builds the installers for **Linux**, **Windows** and **macOS** when a **tag** is pushed, for example:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The artifacts are attached to the corresponding **Release** on GitHub (`.exe`, `.dmg` and `.AppImage` files, depending on which jobs succeeded).

### REST API

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

| Method | URL | Description |
|---------|-----|-------------|
| POST | /sparql/select | Run a SELECT query |
| POST | /sparql/update | Run an UPDATE query |

## Authors

- Mohamed Saber Mahjoub (Main developer and computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Khaoula Charef (computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Mehrez Bey (computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Noha Aqaoui (computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Vitor Tomas Rodrigues Jordã (computer science student at [Institut Galilée](https://galilee.univ-paris13.fr))
- Sylvain Loiseau
