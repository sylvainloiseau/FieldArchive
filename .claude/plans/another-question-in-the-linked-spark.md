# Plan: Surface `rico:name` as a dedicated "Name" section at the top of the entity form

## Context

`rico:name` (`https://www.ica.org/standards/RiC/ontology#name`) is the literal property used to label every entity — it's hardcoded and set as required at entity **creation** time (`create-entity.component.ts:288`, with `Validators.required` on its form control), and it's the top-priority pick for the backend's `bestLabel()` (`RdfEntityService.java:121-127`). Despite that special status, once an entity is opened for **editing** in `entity-details.component.ts`, `rico:name` is treated like any other property: it's bucketed into the RiC-O tab's "Other Properties" list (or, in principle, "Main Properties" if a future edit to `configuration.json` ever lists it there), with no visual distinction from a field like `rico:extent` or `rico:scopeAndContent`. The user wants `rico:name` pulled out of both lists and always rendered as its own section at the very top of the form — before "Main Properties" — regardless of whether it's configured as a main property or not.

## How the property lists are currently built (relevant code)

Per-entity-load pipeline in `entity-details.component.ts`, run (with some inconsistency) from 4 call sites — `ngOnInit` (:577-609), `changeSelectedEntity` (:280-297), `selectEntity` (:627-635), `addPropertyPlaceholder` (:845-882):
1. `buildEntityDetails(properties, ontologyLabels)` (:514-533) — resets `value.entities = []` per ontology, then buckets every backend property into the ontology whose namespace is a prefix of `property.predicate`. This is the raw "Other Properties" backing array (`item.value.entities`, rendered at `entity-details.component.html:289`).
2. `cleanProperties(ontologyLabels, selectedEntity)` (:474-512) — enriches each property with label/cardinality/range/domain from the ontology's parsed property defs, then calls `checkIfEntityPropertyIsInMainProperties` (:368-472), which **splices** matched properties out of `value.entities` and into `mainPropertyMap[typeName]`, attaching a `schema` object. Only called from `ngOnInit` and `addPropertyPlaceholder` today (pre-existing gap in `changeSelectedEntity`/`selectEntity` — not in scope to fix here).
3. `buildMainProperties(ontologyLabels)` (:332-366) — turns `ontologyData.mainProperties` (from `configuration.json`) into `value.main_Properties`, the dict the template renders at `entity-details.component.html:87-89`, keeping only entries for types the entity currently has.

Today `rico:name` is not listed under any type in the `rico` block of `configuration.json`'s `mainProperties`, so it always ends up sitting in `value.entities` (i.e. "Other Properties") on the RiC-O tab. The RiC-O tab is also the default/first tab (`ngOnInit` calls `onTabChange(0)` with a comment "Select the first tab (Rico) by default", and `ontologyOrderComparator` (:535-543) forces the RiC-O namespace first).

Template structure (`entity-details.component.html`): ontology tabs are rendered by `*ngFor="let item of ontologyLabels | keyvalue:ontologyOrderComparator"` (:73), each tab's content containing, in order: the "Main Properties" block guarded by `MainPropertiesisNotEmpty($any(item.value).main_Properties)` (:80-84, list at :87-89), then "Other Properties" (:284-289). The literal-value editing widget (read/edit/delete per value, "add new data type" button) is duplicated at :193-273 (Main) and :408-489 (Other) — this is what will be reused for the new Name block.

## Design

Because `rico:name`'s namespace prefix only ever matches the `rico` ontology bucket in `buildEntityDetails`, extracting it "wherever it's found" (rather than hardcoding a tab check) naturally makes it appear only on the RiC-O tab — which is also the first/default tab, so it still lands at the effective top of the form as opened. No new tab-identity logic is needed.

1. **`entity-details.component.ts`**
   - Add `private readonly RICO_NAME_PREDICATE = 'https://www.ica.org/standards/RiC/ontology#name';`.
   - In `buildEntityDetails`'s reset loop (:516-518), also reset `value.nameProperty = null;` per ontology.
   - Add `extractNameProperty(ontologyLabels: any): void`, modeled on the splice pattern already used in `checkIfEntityPropertyIsInMainProperties` (:404-416, :446-468):
     - For each ontology entry, search `value.entities` for a property whose `predicate === this.RICO_NAME_PREDICATE`; if found, splice it out and assign to `value.nameProperty`.
     - Defensively also search inside `value.main_Properties`'s per-type arrays (in case a future config edit ever lists `name` under `mainProperties`) and splice it out from there too, so it's never shown twice.
     - If not found anywhere (entity has no `rico:name` triple — shouldn't normally happen since creation always sets one, but external/imported entities could lack it), build a placeholder the same way `checkIfEntityPropertyIsInMainProperties` does: `{ predicate: RICO_NAME_PREDICATE, kind: 'literal', values: [], schema: { label, cardinality, ... } }`, sourcing `schema` from `ontologyData.properties.value` (the parsed RiC-O property defs) so the field still renders an empty, editable, correctly-labeled input.
   - Call `this.extractNameProperty(this.ontologyLabels)` as the last step, right after `buildMainProperties(...)`, in all 4 pipeline call sites (`ngOnInit`, `changeSelectedEntity`, `selectEntity`, `addPropertyPlaceholder`), so the Name section stays correct across entity navigation and after adding new properties.

2. **`entity-details.component.html`**
   - Insert a new guarded block right after line 77 (`<div class="flex flex-col gap-3">`) and before the existing "Main Property Fields" comment/block, guarded by `*ngIf="$any(item.value).nameProperty as nameProp"`.
   - Header styled consistently with the existing "Main Properties"/"Other Properties" labels (`text-lg text-blue-900`), reading "Name", with a small mandatory indicator (red "Required" label, following the same `text-red-500`/`text-green-500` convention already used for cardinality badges at :112-121 — red when `nameProp.values` is empty, green otherwise) since there's no existing required-field/asterisk pattern in this component to match instead.
   - Body: adapt the literal-value widget (:193-273) to operate on `nameProp` instead of `property` (read/edit/delete per value via `propValue.editing`, `onRangeDeleted(propValue, nameProp)`, `editEntity()` — all already generic over any property object), dropping the file-viewer button branch (irrelevant to a name field) and the "add new data type" button (a name is single-valued/cardinality 1, no need to add extra literal values).

## Verification

- `cd Frontend && npx tsc -p tsconfig.app.json --noEmit` and `npx ng build --configuration development` — confirm no new errors/warnings.
- Manual, via `ng serve` + backend running:
  - Open an existing entity (created through the app, so it already has a `rico:name` value) — confirm a "Name" section appears at the very top of the RiC-O tab, above "Main Properties", showing the current name, editable in place exactly like before, and that the same value no longer also appears under "Other Properties" (or "Main Properties" if it was ever configured there).
  - Switch to a non-RiC-O tab (e.g. FOAF) — confirm no "Name" section appears there.
  - Edit the name inline and confirm it persists via `editEntity()` as before.
  - Navigate to a different entity via `changeSelectedEntity` (clicking an IRI-valued property's link) and via `backToPreviousEntity` — confirm the Name section updates correctly each time.
  - If reachable, open an entity that genuinely lacks a `rico:name` triple (e.g. certain externally-imported entities) — confirm the Name section still renders with an empty, editable input rather than being silently absent.
