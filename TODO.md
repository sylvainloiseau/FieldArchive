

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
- shacl
  date picker
  validation

- ajouter un import de lameta à partir du répertoire : reprendre le code python

# files and folders view

# relation object

The RIC-O ontology offer a type of entity that are equivalent to a relation between two entities.

For instance, instead of using the objectproperty "xxx" between 1 and 1, we can use an intermediate
entity between 1 and 2, linked with the 

# A new map page

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

- In the table showing the various data source (in the Datasources page), please add a column displaying the number of RDF triples in the corresponding graph.
- pourquoi le bouton "importer" ouvre un file chooser mêem si l'url est là et valide

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
