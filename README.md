# Projet RDF — Application de Gestion de Métadonnées RDF

Ce projet est une application de gestion de métadonnées au format RDF développée dans le cadre d'un projet académique. Elle permet de gérer des archives numériques, d'importer des données depuis des outils externes comme Tropy ou Lameta, et d'interroger les données via des requêtes SPARQL. L'application est construite avec une architecture client-serveur moderne et peut être utilisée aussi bien dans un navigateur qu'en tant qu'application desktop native grâce à Electron.

## Table des matières

- [Architecture](#architecture)
- [Technologies utilisées](#technologies-utilisées)
- [Prérequis](#prérequis)
- [Installation et lancement](#installation-et-lancement)
- [Structure du projet](#structure-du-projet)
- [Fonctionnalités](#fonctionnalités)
- [Lancement avec Electron](#lancement-avec-electron)
- [API REST](#api-rest)

---

## Architecture

L'application suit une architecture trois couches :

- **Frontend** : application Angular qui s'exécute dans le navigateur ou dans Electron
- **Backend** : API REST Spring Boot qui expose les données et gère la logique métier
- **Stockage** : triplestore RDF4J NativeStore qui persiste les données RDF sur disque

La communication entre le frontend et le backend se fait via HTTP REST sur le port 8080. Le frontend tourne sur le port 4200 en développement.

---

## Technologies utilisées

| Couche | Technologie |
|--------|-------------|
| Frontend | Angular 19, Angular Material, Tailwind CSS |
| Backend | Spring Boot 4, Java 17, Maven |
| Stockage RDF | RDF4J 5.2 NativeStore |
| Desktop | Electron 29 |
| Format de données | RDF, Turtle (.ttl), SPARQL |

---

## Prérequis

Avant de lancer le projet, il faut avoir installé sur sa machine :

- Java 17 ou supérieur
- Node.js 18 ou supérieur et npm
- Angular CLI : `npm install -g @angular/cli`
- Maven 3.8 ou supérieur (ou utiliser le wrapper `mvnw` inclus dans le projet)

---

## Installation et lancement

### 1. Cloner le projet

```bash
git clone https://github.com/votre-utilisateur/Projet-RDF.git
cd Projet-RDF
```

### 2. Lancer le backend Spring Boot

Ouvrir un terminal dans le dossier `RDF_Back` :

```bash
cd RDF_Back
./mvnw spring-boot:run
```

Sur Windows :

```bash
cd RDF_Back
mvnw.cmd spring-boot:run
```

Le backend démarre sur `http://localhost:8080`. Attendre le message :

```
Started RdfBackApplication in X seconds
```

### 3. Lancer le frontend Angular

Ouvrir un second terminal dans le dossier `Frontend` :

```bash
cd Frontend
npm install
ng serve
```

L'application est accessible sur `http://localhost:4200`.

---

## Structure du projet

```
Projet-RDF/
├── Frontend/                        # Application Angular
│   └── src/app/
│       ├── components/
│       │   ├── gestion-projets/     # Page de gestion des projets
│       │   ├── gestion-sources/     # Page de gestion des sources de données
│       │   └── gestion-ressources/  # Page de gestion des entités RDF
│       ├── services/
│       │   ├── project.service.ts   # Appels API projets
│       │   └── data-source.service.ts # Appels API sources de données
│       └── models/
│           └── data-source.model.ts
├── RDF_Back/                        # Backend Spring Boot
│   └── src/main/java/com/uspn/rdf_back/
│       ├── controllers/             # Endpoints REST
│       ├── services/                # Logique métier
│       ├── dtos/                    # Objets de transfert
│       └── config/                  # Configuration CORS et RDF4J
├── electron/                        # Couche Electron (application desktop)
│   ├── main.js                      # Processus principal Electron
│   └── preload.js                   # Bridge sécurisé
├── data/rdf-store/                  # Données RDF globales persistées
├── projects/                        # Données RDF par projet
└── package.json                     # Configuration Electron
```

---

## Fonctionnalités

### Gestion des projets

L'application utilise un système de projets. Chaque projet possède son propre triplestore RDF4J isolé. Depuis la page d'accueil, il est possible de créer un nouveau projet, de lister les projets existants et d'en ouvrir un. Un seul projet peut être actif à la fois.

### Gestion des sources de données

Une source de données correspond à un graphe nommé dans le triplestore RDF4J. Il existe deux types de sources :

- **Interne** : les données sont saisies directement dans l'application et sont modifiables
- **Externe** : les données sont importées depuis un fichier RDF produit par un outil tiers (Tropy, Lameta, Gramps). Ces sources sont en lecture seule et peuvent être ré-importées à tout moment

### Gestion des ressources

La page de gestion des ressources permet de visualiser, filtrer et naviguer dans les entités RDF du projet actif. Les entités sont organisées par type : Event, Person, Record, Instantiation, Agent, Place, Record Resource.

### Requêtes SPARQL

Le backend expose un endpoint SPARQL qui permet d'interroger et de modifier les données du triplestore via des requêtes SELECT et UPDATE.

---

## Lancement avec Electron

Electron permet de lancer l'application comme une application desktop native sans avoir besoin d'un navigateur.

### Installation des dépendances Electron

A la racine du projet :

```bash
npm install
```

### Mode développement

S'assurer que le backend et le frontend Angular tournent déjà (voir étapes 2 et 3 ci-dessus), puis dans un troisième terminal à la racine :

```powershell
$env:NODE_ENV="development"
npx electron .
```

Une fenêtre native s'ouvre et charge l'application Angular depuis `localhost:4200`.

### Mode production

Compiler d'abord le frontend et le backend :

```bash
npm run build:frontend   # compile Angular dans electron/dist/frontend
npm run build:backend    # compile Spring Boot en JAR
```

Puis lancer :

```bash
npx electron .
```

En mode production, Electron charge les fichiers statiques Angular compilés et lance le backend Spring Boot automatiquement en arrière-plan.

### Générer un installateur Windows

```bash
npm run dist
```

Cela génère un fichier `.exe` installable dans le dossier `dist-electron/`.

---

## API REST

Le backend expose les endpoints suivants sur `http://localhost:8080/api` :

### Projets

| Méthode | URL | Description |
|---------|-----|-------------|
| GET | /api/projects | Lister tous les projets existants |
| POST | /api/projects/open | Ouvrir ou créer un projet |
| GET | /api/projects/current | Obtenir le projet actuellement actif |
| POST | /api/projects/close | Fermer le projet actif |

### Sources de données

| Méthode | URL | Description |
|---------|-----|-------------|
| GET | /api/datasources | Lister toutes les sources |
| POST | /api/datasources/internal | Créer une source interne |
| POST | /api/datasources/external | Créer une source externe |
| PUT | /api/datasources/{name} | Modifier une source |
| DELETE | /api/datasources/{name} | Supprimer une source |
| POST | /api/datasources/{name}/sync | Ré-importer une source externe |

### SPARQL

| Méthode | URL | Description |
|---------|-----|-------------|
| POST | /api/sparql/select | Executer une requête SELECT |
| POST | /api/sparql/update | Executer une requête UPDATE |

---

## Notes

Le projet utilise un triplestore RDF4J NativeStore qui persiste les données sur disque dans les dossiers `data/` et `projects/`. Ces dossiers ne doivent pas être supprimés sous peine de perdre les données. Il est recommandé de les ajouter au `.gitignore` si les données sont confidentielles.
