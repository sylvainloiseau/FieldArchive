# Include subtypes when picking an existing entity as a property's object

## Context

In `entity-details.component.ts`, when editing an object-property (IRI-valued), the dropdown offering existing entities to use as the value is populated by fetching entities whose `rdf:type` **exactly** matches one of the property's declared `rdfs:range` type(s). Per RDF/RDFS semantics, an entity typed with any **subclass** of a declared range is also a valid object of the property (e.g. a property ranging over `rico:Agent` should accept entities typed `rico:Person` or `rico:Group`, since both are `rdfs:subClassOf rico:Agent`). Today those subtype instances never appear in the dropdown, silently under-listing valid choices.

A property can have **several** declared ranges (`entity-details.component.ts` already distinguishes the single-range case, `getEntitiesByType(rangeUri: string)`, from the multi-range case, `getEntitiesByTypes(rangeUris: string[])`). The correct semantics for the multi-range case is the **union**, across all declared ranges, of (range ∪ its subtypes) — not just widening each range independently and concatenating, since that can otherwise double-list an entity that matches more than one range's subtype tree (e.g. two declared ranges where one is itself an ancestor of the other, or that simply share a common ontology). The current multi-range code already hints at this need but doesn't fully solve it: it fires one HTTP request per declared range (`forkJoin`) and merges with a plain `results.flat()` — no de-duplication by entity IRI. Rather than parallelizing N under-specified requests, the fix computes the whole union server-side, in one request, with proper de-duplication.

The class hierarchy needed to compute subtypes already exists (parsed once per ontology file, used today only to walk *upward* — e.g. `expandWithSuperClasses` in `entity-details.component.ts`); nothing in the codebase currently walks it *downward* to find descendants.

The fix is scoped to the property-object picker only (per prior decision): the same backend call is also used by the main resource table (TypeNavigator), and widening it unconditionally would change that unrelated browsing behavior (e.g. clicking "Agent" would then also list every Person/Group). The fix is therefore additive: a new opt-in flag, defaulting to today's exact-match behavior everywhere else.

## Backend changes

**`Backend/src/main/java/fr/cnrs/lacito/fieldarchive/services/OntologyService.java`**

- Add a descendants index, built once alongside the existing `preloadSchemas()` call in the constructor (`OntologyService()`, ~line 148), by inverting the ancestor edges already computed per ontology by `OntologySchemaExtractor.extractHierarchy` (exposed as `OntologySchemaDto.getHierarchy()`, child → direct superclass URIs):
  ```java
  private Map<String, Set<String>> descendantsIndex; // superclass URI -> direct subclass URIs

  private void buildDescendantsIndex() {
      descendantsIndex = new HashMap<>();
      Map<String, Object> ontologies = (Map<String, Object>) ontologyConfig.get("ontologies");
      if (ontologies == null) return;
      for (String prefix : ontologies.keySet()) {
          Map<String, List<String>> hierarchy = getOntologySchema(prefix).getHierarchy();
          if (hierarchy == null) continue;
          hierarchy.forEach((child, supers) -> supers.forEach(sup ->
              descendantsIndex.computeIfAbsent(sup, k -> new LinkedHashSet<>()).add(child)));
      }
  }
  ```
  Call `buildDescendantsIndex()` right after `preloadSchemas()` in the constructor (it depends on `schemaCache` being populated). This mirrors the merge-across-all-ontologies approach already used by the frontend's `expandWithSuperClasses`, so it stays correct for every `DefinedOntology`, not just RiC-O (RiC-O is the only ontology whose triples live in the running triplestore — a live SPARQL `rdfs:subClassOf*` query would silently miss FOAF/PNV/etc. ranges, which is why this reuses the file-parsed schema hierarchy instead).

- Add a public traversal method used by `RdfEntityService`:
  ```java
  /** Returns typeIri plus every transitive subclass known from the loaded ontology schemas. */
  public Set<String> expandWithSubtypes(String typeIri) {
      Set<String> result = new LinkedHashSet<>();
      Deque<String> toVisit = new ArrayDeque<>();
      result.add(typeIri);
      toVisit.push(typeIri);
      while (!toVisit.isEmpty()) {
          for (String child : descendantsIndex.getOrDefault(toVisit.pop(), Set.of())) {
              if (result.add(child)) toVisit.push(child);
          }
      }
      return result;
  }
  ```

**`Backend/src/main/java/fr/cnrs/lacito/fieldarchive/services/RdfEntityService.java`**

- Replace `listByType(String typeCurieOrIri)` (~line 289) with `listByTypes(List<String> typeCuriesOrIris, boolean includeSubtypes)`, which handles both the single- and multi-range case uniformly (single range is just a one-element list):
  ```java
  public List<RdfEntitySummaryDto> listByTypes(List<String> typeCuriesOrIris, boolean includeSubtypes) {
      requireProjectOpen();
      if (typeCuriesOrIris == null || typeCuriesOrIris.isEmpty()) {
          throw new BadRequestException("Type parameter is mandatory.");
      }

      // Union, across every declared range, of (range ∪ its transitive subtypes).
      Set<IRI> candidateTypes = new LinkedHashSet<>();
      for (String typeCurieOrIri : typeCuriesOrIris) {
          String seedIri = expand(typeCurieOrIri);
          Set<String> seeds = includeSubtypes
                  ? ontologyService.expandWithSubtypes(seedIri)
                  : Set.of(seedIri);
          for (String s : seeds) candidateTypes.add(vf.createIRI(s));
      }

      // De-dupe by subject: an entity may match more than one candidate type
      // (e.g. two declared ranges whose subtype trees overlap).
      Map<IRI, RdfEntitySummaryDto> bySubject = new LinkedHashMap<>();
      try (RepositoryConnection conn = ProjectContext.getRepository().getConnection()) {
          for (IRI typeIri : candidateTypes) {
              try (var stmts = conn.getStatements(null, RDF.TYPE, typeIri)) {
                  while (stmts.hasNext()) {
                      Resource s = stmts.next().getSubject();
                      if (!(s instanceof IRI subject)) continue;
                      bySubject.computeIfAbsent(subject, subj -> {
                          boolean internal = isInternalEntity(conn, subj);
                          RdfEntitySummaryDto dto = new RdfEntitySummaryDto();
                          dto.entityKey = keyFromIri(subj);
                          dto.iri = subj.stringValue();
                          dto.source = internal ? "internal" : "external";
                          dto.editable = internal;
                          dto.label = bestLabel(conn, subj);
                          dto.creationDate = getCreationDate(conn, subj);
                          dto.modificationDate = getModificationDate(conn, subj);
                          return dto;
                      });
                  }
              }
          }
      }

      List<RdfEntitySummaryDto> out = new ArrayList<>(bySubject.values());
      out.sort(Comparator.comparing(a -> a.label == null ? "" : a.label));
      return out;
  }
  ```
  `RdfEntityService` already has `OntologyService ontologyService` injected in its constructor — no new wiring needed. `listByType(String)` had exactly one caller (`RdfEntityController`, updated below), so it is removed rather than kept as dead code, consistent with this session's earlier cleanup precedent.

**`Backend/src/main/java/fr/cnrs/lacito/fieldarchive/controllers/RdfEntityController.java`**

- `listEntities` (~line 28): accept repeated `type` query params (`?type=a&type=b`, and a single `?type=a` still binds to a one-element list — fully backward compatible with every existing caller), plus the new opt-in flag:
  ```java
  @GetMapping("/entities")
  public List<RdfEntitySummaryDto> listEntities(
          @RequestParam(value = "type", required = false) List<String> types,
          @RequestParam(value = "includeSubtypes", required = false, defaultValue = "false") boolean includeSubtypes) {
      if (types == null || types.isEmpty()) {
          return service.listWithoutType();
      }
      return service.listByTypes(types, includeSubtypes);
  }
  ```
  Default `includeSubtypes=false` and single-`type` requests preserve current behavior for every existing caller (including the TypeNavigator/main table) with zero frontend changes required there.

## Frontend changes

**`Frontend/src/app/services/gestion-ressources.service.ts`**

- Change `getAllEntitiesByType(typeUrl: string | '')` (~line 110) to accept either one type or several, plus the opt-in flag, building repeated `type` params for the multi-range case:
  ```ts
  getAllEntitiesByType(typeUrl: string | string[] | '', includeSubtypes = false): Observable<any> {
    if (typeUrl === '') {
      return this.http.get<any>(`${this.rdfUrl}/entities`);
    }
    const typeUrls = Array.isArray(typeUrl) ? typeUrl : [typeUrl];
    let params = new HttpParams();
    for (const uri of typeUrls) {
      params = params.append('type', uri);
    }
    if (includeSubtypes) params = params.set('includeSubtypes', 'true');
    return this.http.get<any>(`${this.rdfUrl}/entities`, { params });
  }
  ```
  This keeps the existing single-string call sites (`gestion-ressources.component.ts`'s `getAllEntitiesWithoutType`/`getAllEntitiesByPath`, TypeNavigator/main table) source-compatible and behaviorally unchanged (still exact-match, still one `type` param).

**`Frontend/src/app/components/entity-details/entity-details.component.ts`**

- `getEntitiesByType(rangeUri: string)` (~line 915): pass `true` for the new flag:
  ```ts
  this.gestionRessourceService.getAllEntitiesByType(rangeUri, true).subscribe({ ... });
  ```
- `getEntitiesByTypes(rangeUris: string[])` (~line 884): replace the `forkJoin` fan-out + `results.flat()` with a single call, letting the backend compute the de-duplicated union:
  ```ts
  getEntitiesByTypes(rangeUris: string[]): void {
    this.listOfAllRanges = [];
    if (!rangeUris || rangeUris.length === 0) { return; }
    const uniqueRangeUris = [...new Set(rangeUris)];
    this.gestionRessourceService.getAllEntitiesByType(uniqueRangeUris, true).subscribe({
      next: (res) => { this.listOfAllRanges = res; },
      error: (err) => { console.error("Error fetching possible ranges: ", err); }
    });
  }
  ```
  This both adds subtype-widening AND fixes the pre-existing lack of de-duplication across ranges, in one pass — the `forkJoin`/`import` of `forkJoin` in this file may become unused if nothing else in the component relies on it (check before removing the import).
- Leave `gestion-ressources.component.ts`'s two call sites (`getAllEntitiesWithoutType`, `getAllEntitiesByPath`, main table/TypeNavigator) untouched — they keep calling with a single string and the default `includeSubtypes = false`, preserving exact-type browsing.

## Drive-by note (not fixed unless requested)

While reading `getEntitiesByType`, its "already loaded, skip refetch" guard (`lastLoadedRangeUri` check) is dead code: `listOfAllRanges` is cleared unconditionally the line before the check, so the length check is always `0` and the early return never fires. This causes a harmless but wasteful refetch on every dropdown click. Out of scope for this fix; flagging only — happy to clean it up separately if wanted.

## Verification

1. `cd Backend && mvn -q compile` — confirm the new/changed Java compiles.
2. `cd Frontend && npx tsc -p tsconfig.app.json --noEmit` — confirm no new TS errors (and check whether `forkJoin` becomes an unused import in `entity-details.component.ts`).
3. Manual end-to-end check (RiC-O ships `rico:Person` and `rico:Group` as subclasses of `rico:Agent`):
   - Start backend (`FIELD_ARCHIVE_DATA=<dir> mvn spring-boot:run`) and frontend (`ng serve`).
   - Create/open a project; create one entity typed `rico:Person` and one typed `rico:Group` (or reuse existing sample data with such entities).
   - **Single-range case:** open an entity and add a property whose declared range is `rico:Agent` — confirm both the Person- and Group-typed entities now appear in the object dropdown, not just entities typed exactly `Agent`.
   - **Multi-range case:** find (or temporarily use) a property with more than one declared range where the ranges' subtype trees overlap (e.g. one range is an ancestor of the other) — confirm every matching entity appears exactly **once** in the dropdown, not duplicated.
   - Separately, in the TypeNavigator/main table, click on a supertype node (e.g. `Agent`) and confirm the table still lists only entities typed exactly `Agent` — i.e. confirm the scoped flag did not leak into the unrelated browsing path.

---

## Follow-up: grouping the dropdown by entity type

After the above was implemented (backend `listByTypes`/`expandWithSubtypes`, frontend using `includeSubtypes=true`), a second round of changes grouped the dropdown entries by the entity's own type, using a new `matchedTypes` field returned per entity (the subset of candidate types — i.e. the declared range(s) expanded with subtypes — that the entity is actually asserted with via `rdf:type`), rendered as one `<optgroup>` per type, sorted alphabetically by local name, with an entity appearing once per matching group. A pre-existing dead-code guard in `getEntitiesByType` (the "already loaded, skip refetch" check, defeated because `listOfAllRanges` was cleared before the check ran) was also fixed at that point.

## Q&A

**Q (user):** I would like to be sure about the logic of the app regarding the important changes we made about the drop-down for selecting the objects of a property in `entity-details.component.ts`. Lets say that a property has a range of "rico:Event". And lets say that an entity has types `rico:Activity`, `rico:Event` and `rico:Person`. In that case, the entity should be selected because *it has at least one type that is the range of the property* (the range of the property is `rico:Event`, and the entity has `rico:Event` and `rico:Activity`, which is a sub-type of Event.) Then it should be displayed twice in the drop down under an `Event` section, and under an `Activity` Section. However, it should not appear under a `rico:Person` section, event it has that type, because it's not a type or a subtype of the range. Is this what the app do?

**A (assistant):** Yes, that's exactly what the app does. Walking through the example (property range = `rico:Event`, entity typed `rico:Activity` + `rico:Event` + `rico:Person`):

1. Candidate type set is built from the range only, not from the entity's types (`RdfEntityService.java:296-303`): `expandWithSubtypes("Event")` yields `{Event, Activity, ...other Event subtypes}`. `rico:Person` is never in `candidateTypes` — it isn't a subclass of `Event`.
2. The entity is matched once per candidate type it's explicitly asserted with (`RdfEntityService.java:312-332`): the code iterates `candidateTypes` and for each one queries `conn.getStatements(null, RDF.TYPE, typeIri)`. Since the entity has explicit `rdf:type` triples for both `Event` and `Activity`, it's found on both iterations, and `matchedTypesBySubject` accumulates both. `Person` is never iterated at all, so the entity is never even checked against it.
3. Result: `matchedTypes = ["Event", "Activity"]` for that entity — `Person` never enters the picture.
4. The frontend groups strictly by `matchedTypes` (`buildGroupedRanges` in `entity-details.component.ts`): the entity is pushed into the `Event` group and the `Activity` group (once each); since no `Person` group is ever created for this query, the entity cannot appear there.

So: shown twice (under "Event" and "Activity"), never under "Person" — matches the expected behavior exactly.
