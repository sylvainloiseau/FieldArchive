mettre des noms plus précis, adherant aux naming conventions

when editing the string value of property whose range is Literal in the entity-details component, if I hit the small validating button in the right side of the text field, it does update the value. However, if I hit enter, it does not. It is possible to correct this?

- `SparqlService` classifies queries by substring-matching lowercased text, so a SELECT whose variable or IRI contains e.g. "create" is rejected.
- `OntologyService`'s `schemaCache` and the config map are loaded once at construction; ontology files and `configuration.json` changes require a backend restart.

Pouryuqoi les ExternalDataSource ne sont plus éditables?

Mettre en gras les onglets où il y a des propriétés

## Out of scope (flagged, not fixed)

In DataSourceManagement component:

- **Remote `http(s)://` locations.** The field is named `url` and an earlier placeholder promised `https://example.com/data.rdf`, but sync only ever opens a filesystem path.
- `sourceTool`/`tool` is never captured or stored (no form control, no DTO field), so the table's "Source" column always shows `-`.
- `isFileLoading` is set at component `:106` and never reset to `false`.
- `exportAsCSV()` lists a `Tool` header but omits the value, so CSV columns are off by one.
- Both import paths hardcode `RDFFormat.TURTLE`; `.rdf`/`.owl` exports will not parse.
