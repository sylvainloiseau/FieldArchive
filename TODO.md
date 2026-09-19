- when editing the string value of property whose range is Literal in the entity-details component, if I hit the small validating button in the right side of the text field, it does update the value. However, if I hit enter, it does not. It is possible to correct this?
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


# Create a shared header.

Could you please check that there is a true header, shared by all the application pages.

When no project is open, only the "project" button is displayed on the right of the bar.

When a project is open, the buttons "DataSource", "Entities", "Sparql" are displayed, separated by some space from the project button.

If a project is closed, the buttons are disable again.

The name of the open project should be displayed on the left, as it is now, in a consistent way. When the projet is closed, the project name is removed from the header.

The gestion-ressources.component.html as a view toggle based on the value of the activeView variable. It has it's own toolbar. Could you please 

- make a separate component for the sparql query/result area, which is currently in <app-sparql> in gestion-ressources component


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
