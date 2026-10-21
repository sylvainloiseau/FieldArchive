## Create a gramps converter

Reading the rml file.

## Create a directory converter

Convert the python script

## Create a spreadsheet converter

Convert the python script

## Create a tropy converter

- Either from the db: https://github.com/tropy/tropy/blob/main/db/schema/project.sql
- Or from the export (jsld)

## OWL and ontology definition

overloaded in project definition

## better variable name

Please audit the codebase and look for better variable and method/function names.

Please use the concepts of the application:
- "internal data source",
- "external data source", 
- "project ontologies", 
- "project property graph": the named graph in the triple store of a project that stores application data about the project (description, named queries, external data source...); 
- DefinedOntologies : ontologies pour lesquelles, dans le répertoire de configuration de l'application, il y a le fichier OWL définissant l'ontologie ET il y a une section correspondante dans le fichier JSON de configuration de l'application. Pour simplifier, disons qu'une ontologie pour laquelle il n'y a que l'un ou l'autre mais pas les deux est ignorée (n'est pas comptée dans la liste des defined ontologies)
- ProjectOntologies : all ontologies used in a project; it could be an ontology type (object of a rdf:type property), or a property present through one of its property. Project ontologies may be larger than the DefinedOntologies: a external data source in particular can contain any ontologies.
- UsedOnlyOntologies : ontologie rencontrée dans le projet mais qui n'est pas dans les DefinedOntology
- CreateDialogue
- TypeSelectorDialog : boite de dialogue pour ajouter des types à une entité. Cette boîte de dialogue affiche les types présent dans les DefinedOntology, pas les types qui sont dans les UsedOnlyOntology : cette boîte de dialogue offre les types dont on connait les ontologies. Si un utilisateur veut pouvoir ajouter des types appartenant à une autre ontologie, il doit d'abord éditer le fichier JSON et ajouter la définition OWL dans un répertoire.
- PropertySelectorDialog
- OntologiesParameters Paramètres d'une ontologie définie pour l'application LinguisticFieldArchive. Les OntologiesParameters sont lues par l'application LinguisticFieldArchive et permettent de déclarer (1) les ontologies avec lesquelles on peut éditer les données dans l'application et (2) de paramètres comment ces ontologies s'affichent (cf. mainType et mainProperty).
- mainType : énumère les types les plus importants d'une ontologie, pour les rendre plus accessible à l'utilisateur. Défini dans les OntologiesParameters.
- mainProperties : pour certains types d'une ontologie, définit des propriétés principale. Permet de définir une sorte de formulaire pour chaque type. Défini dans les OntologiesParameters.
- NavigateurDeType : hiérarchie affichée à gauche dans la page d'édition des entités regroupant les entités par types. Cet arbre vise à permettre la navigation dans les entités existantes dans le projet : affiche les types effectivement trouvés dans le projet, pas les types définis dans des ontologies mais pas utilisés.

If you can think of other concepts that can be extracted from the application behavior please mention them in a glossary and use them as variable / method name.

## Sparql

Could you make a plan for restyling the sparql page. Please ask any question regarding how it could be restyle and new usefull functionalities be added. This could include:

- Query history (including query runned via the "Ask about this project" Dialog for AI-assisted query generation)
- link for inserting PREFIX declaration for the ontologies used in the project.

## relation entity

The RIC-O ontology offer a type of entity that are equivalent to a relation between two entities.

For instance, instead of using the objectproperty "xxx" between 1 and 1, we can use an intermediate
entity between 1 and 2, linked with the 

## On an entity-detail page, also display the triple where the entity is the object

For instance: in which event participate an individual, etc.

## File viewer

## Redesign the Entity page

A setting page for defining the display of entities.
Use the same categorisation that for data matching algorithm parameter page:
 - person
 - etc
For each of this group, allows to define type from the existing ontology; subtype, etc.
For each of this group, define a strategy of representation: name, etc.

## Others

- `SparqlService` classifies queries by substring-matching lowercased text, so a SELECT whose variable or IRI contains e.g. "create" is rejected.
- `OntologyService`'s `schemaCache` and the config map are loaded once at construction; ontology files and `configuration.json` changes require a backend restart.
- Pourquoi les ExternalDataSource ne sont plus éditables?
- mettre un sablier pour une opération longue comme récupérer les entités matchant un range.
- retirer les entités déjà attribuer à la propriété du drop down des entités offerte à cette propriété.
- Mettre en gras les onglets où il y a des propriétés
- In DataSourceManagement component:
  - **Remote `http(s)://` locations.** The field is named `url` and an earlier placeholder promised `https://example.com/data.rdf`, but sync only ever opens a filesystem path.
  - `sourceTool`/`tool` is never captured or stored (no form control, no DTO field), so the table's "Source" column always shows `-`.
  - `isFileLoading` is set at component `:106` and never reset to `false`.
  - `exportAsCSV()` lists a `Tool` header but omits the value, so CSV columns are off by one.
- Both import paths hardcode `RDFFormat.TURTLE`; `.rdf`/`.owl` exports will not parse.


## shacl validation

## date picker


## Record view


## files and folders view

repartir du ticket github.

Ajouter des folders. Pour un folder, filtrer les fichiers

Vue agrégée. Pour un fichier, lier à un object instantiation ou en créer un. dans le fenêtre, lier à un objet Record ou en créer un. Lier à un Event ou en créer un
lier à d'autres objets instanciation

## A new map page

Could you add a new page to the application, thanks to a new angular component.

The page will be called "map" and will have a button on the shared header, before the "sparql" button.

The page will contains a map, using a well-maintened map component, such as MapLibre GL, online OpenStreetMap tiles and attributions

Online tiles are fine, even though linguistic fieldwork is often done offline.

You will extract, in the project entities, the entity of type "rico:Coordinates". These entities have a dataproperty "rico:longitude" and "rico:latitude" that you will used to positionate the point on the map. For each entity of type "rico:Coordinate", you will then look for an entity of type "rico:PhysicalLocation" that has an object property "hasOrHadCoordinates" pointing to this entity URI. Again, you will look for an entity of type "rico:Place" that has an objectproperty "hasOrHadPlaceType" pointing to the place type entity URI. Then you can use the rico:name of this Place entity in order to draw a label on the map at the coordinates given by the rico:Coordinates entity. Before actually building a plan for this mission, could you tell me which other pieces of information would be usefull to you in order to build a plan?

 So the chain you will implement is Coordinates ← PhysicalLocation ← Place, using hasOrHadCoordinates and then hasOrHadPhysicalLocation, and taking the label from rico:name of the Place.

On each Place entity, there is also a "hasOrHadPlaceType" objectproperty, pointing to PlaceType. you will use the PlaceType name to create a filter: by clicking on each PlaceType, the user can display or hide the Place having the corresponding PlaceType.

## Edge cases

- when a Coordinates has no PhysicalLocation, when a PhysicalLocation has no Place, or when the Place has no rico:name? Options are to skip the point, or to show a marker without a label or with the raw coordinates.

- If one Coordinates is linked to several Places, should I draw one label per Place or merge them?

- Should the label always be visible, or only on hover or click? Should clicking a marker open the existing entity-details dialog?

- Should nearby markers be clustered, and should the map zoom to fit all points on load?

- Should points from external data sources appear too? Everywhere else in the app internal and external data are shown together with a badge, so my default is yes.

## Interaction

Interaction: the label is always shown, clicking opens the entity dialog, and the map zooms to fit all points.

## DATA

Sample data: which dataset or project has Coordinates entities to test with? I can search samples/ if you’d like.
Coordinate format: are latitude/longitude plain decimal numbers (12.6392), or do they include degrees/minutes (12°38′N) or decimal commas? The ontology allows any of these, so I’d need a parsing rule.

# End point

Data access: a dedicated backend endpoint doing one SPARQL query, rather than raw SPARQL from Angular.

# Done

## Varia

- In the table showing the various data source (in the Datasources page), please add a column displaying the number of RDF triples in the corresponding graph.
- pourquoi le bouton "importer" ouvre un file chooser mêem si l'url est là et valide

## restyle entity-detail

Please could you make a plan for updating the style and the GUI components used in the "entity-detail" angular component. There is nothing to change in the GUI logic and the code (however please report bugs that you may find), but I would like to use the same component and style used in the data matching and data-matching setting pages (cf the "data-matching" angular component).

## data reconciliation algorithm

The application is build on the concept of "data source": a given archive/project is made of one or several data source: at least one "internal data source" (a modifiable RDF graph) and zero or several "external data source" (a read-only RDF graph, whose content is synced with an external application, where the data are actually edited).

A classic issue in this situation is that, in different several data sources, the same real-world entity can be refered to with a different ID. This is a classical record-linkage, deduplication, or data matching issue.

I would like you to make a detailled plan for a maching algorithm that will help to identify and memorize different IRIs refering to the same real-world entity accross data source.

The matching algorithm is described in Python in a notebook in the file at "Data Matching/Data_Matching.ipynb". You will audit this algorithm and make plan to translate it into java into the backend, making explicit the logic and the different steps with clear variable and method names. 

The algorithm include reading a json file with parameter for classes.

The main step of the algorith is to :
  - consider all pairs of data source
    - for each pair of data source, consider each pair of IRI where each IRI belongs to a different data source,
      - excluding all pairs with an IRI that is already matched
      - excluding the IRI pairs that have been explicitely rejected
      - compute a similarity score (cf. the algorithm)
      - store the result in a json file in the project folder

You will then design a new page "data matching", that can be accessed through a button on the datasource page. It will connect to the backend algorithm with new REST contact point. It will contains a graphical interface allowing the user to select each pairs of data source and to validate or reject the matching-proposal from the algorithm, and allowing him or her to define manually a matching. Status (accepted, rejected, manually set) will also be stored in the same json file in the project folder.

Please ask any question regarding this plan, do not hesitate to suggest improvements in the algorithm and the GUI, try to elaborate a very clean and future-proof design for both the algorithm and the GUI.

## An import system for lameta

I would like you to make a plan for the following functionality. It impacts both the frontend and the backend. Please make a deep analysis of this instruction; take care to offer a clean and future-proof design; ask as many question as needed and offer suggestion of other functionalities or better ones if you can think of some.

I have defined a plugin system for creating importers for FieldArchive. Importer will take care of the data of a specific app and convert it into RDF in order to import or re-import it into an external data source.

The plugin source code is defined in the ../ricImporterPluginof directory. It defines a service `fr.cnrs.lacito.lameta2rdf.Lameta2RdfConverterProvider`. It is deployed on the local .m2 maven repository, and also available with the coordinate `io.github.sylvainloiseau:ricimporterplugin:0.1.0-SNAPSHOT` on the following directory:

```
  <repositories>
    <repository>
      <id>github</id>
      <name>GitHub Packages</name>
      <url>https://maven.pkg.github.com/sylvainloiseau/*</url>
    </repository>
  </repositories>
```

You will add a dependency to this plugin definition to the pom.

An implementation of this plugin exist for the lameta software, that can provide data for an external data source. This implementation is in the ../lemeta2rico directory; its maven coordinate is io.github.sylvainloiseau:lameta2rico:0.1.0-SNAPSHOT, it is in the same repository as the previous one mentioned above.

I want you to add to the pom the instruction to download this maven plugin and put it in a ricImporterPlugins directory during the build, and then to include this directory during the building of the backend. I would like to add to the backend startup a mechanism for searching plugins declaring `fr.cnrs.lacito.lameta2rico.Lameta2RdfConverterProvider` in both the `ricImporterPlugins` directory shipped within the application and also into an `ricImporterPlugins` directory inside the parameter directory of the application in the user filesystem, in which is also the projects triple store for instance. You will use URLClassLoader for loading the plugins.

In the data source page, the column "Source" display the source of the external entity, for instance "Lameta", "Gramps"... However the corresponding field does not exist any more when editing the data source. I think it has been commented out. I would you to reintroduce this button as a drop down in the data source editing pannel. In order to populate the drop down, you will list by their name the plugins that were found, and add at the end an "sync with an rdf file". For now, then, the drop down will contain only two values: one for the lameta importer, the other for the syncing with an rdf file. If a data source has a value that do not match the name of one of the loaded plugins, you will grayed the dropdown, display the value below it with the following message "No plugin for this data source: <name found>." Please put the plugin in your the FieldArchive configuration directory or turn the value to raw rdf". Besides, two buttons "open the plugin directory" and "turn the value to raw rdf". The first one open the plugin directory; the second replace the value found by the value "sync with an rdf file".

When the drop-down has the value "sync with an rdf file" and that the user select the `import` button, the behavior stay as today: the file at the the given filepath is read and its content replace the content of the named graph.

By contrast, when another value than "sync with an rdf file" is selected, the corresponding plugin is called and the filepath or directory path argument is transmitted to it. The result provided by the plugin through its interface is used to replace the triples of the named graph, with the context name defined in the Data source.
