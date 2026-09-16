rajouter dans Claude le cas délicat des 
- propriété ont plusieurs range.
- quand on sélectionne un range dans la fenêtre de sélection des propriétés, il faut que ça sélectionne toutes les propriétés qui ont parmi leur range celui-ci, ou l'un de ses super-types
- une fois une propriétés sélectionnées, dans le drop down pour la sélection de l'entité, il faut afficher les entités qui ont comme type au moins l'un des ranges de la propriétés ou n'importe lequel de leur sous-type.


- franciser

auditer le code base

mettre des noms plus précis, adherant aux naming conventions

when editing the string value of property whose range is Literal in the entity-details component, if I hit the small validating button in the right side of the text field, it does update the value. However, if I hit enter, it does not. It is possible to correct this?

In the file CLAUDE.md, under the section "## Known rough edges", four bugs are reported. Could you offer a plan for correcting these four bugs?

## Known rough edges

- `DataSourceService.createExternalDataSource` never writes `app:sourceLocation` (the write is commented out), so `synchronizeExternalDataSource`, which reads it back, fails with "Graphe ou chemin du fichier manquant" for sources created through the UI.
- `DataSourceService.deleteDataSource` guards the literal shortName `"internal"`, but internal sources are actually named `<projectName>_internal`, so the guard never fires.
- `SparqlService` classifies queries by substring-matching lowercased text, so a SELECT whose variable or IRI contains e.g. "create" is rejected.
- `OntologyService`'s `schemaCache` and the config map are loaded once at construction; ontology files and `configuration.json` changes require a backend restart.

Why the following error, each type I click on a type in the type selector, or that I open an entity entity-detail component?

No refresh of the entity table after the closing of an entity-detail window

After "create", on an entity create window: close the window

Remove:
"An internal data source already exists. You can only create external sources."




[Backend] 2026-09-15T18:54:16.825+02:00  WARN 60922 --- [RDF_Back] [      Cleaner-3] o.e.rdf4j.sail.helpers.CleanerIteration  : Forced closing of unclosed iteration. Set the system property 'org.eclipse.rdf4j.repository.debug' to 'true' to get stack traces.
2026-09-15T18:54:16.825+02:00  WARN 60922 --- [RDF_Back] [      Cleaner-3] o.e.rdf4j.sail.helpers.CleanerIteration  : Forced closing of unclosed iteration. Set the system property 'org.eclipse.rdf4j.repository.debug' to 'true' to get stack traces.
