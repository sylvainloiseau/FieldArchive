# Restyle of `entity-details` and its dialogs — plan

Goal: give the entity editor (`components/entity-details/`) and the dialogs it opens the look
and the GUI components of the data matching pages (`components/data-matching/`,
`components/matching-settings/`), and fix the bugs found while reading the code.

> **Status (2026-10-17): implemented**, not committed. Differences from this plan:
> - The fixes, the clean-up and the restyle were done together (one set of changes); the binding
>   audit compares the old and the new template.
> - `confirm-delete-dialog` and `confirm-delete-property` were deleted (the editor was their only
>   user) instead of being kept as aliases; `simple-confirm-dialog` too (matching settings).
> - Additional bug found and fixed, **B21** (backend): the names of linked entities were read in
>   the internal graph only and through `rico:name` only, so links to external entities showed
>   bare IRIs; and a link to an IRI without any statement threw "Entité introuvable", which
>   prevented opening the entity. `RdfEntityService.linkedEntityName` reads `LABEL_PREDICATES`
>   in every graph and returns "".
> - `mat-chip-grid` without a text input threw an error at each display (also before the
>   restyle): the type chips use `mat-chip-set`.
> - Tailwind v4 draws uncoloured borders in `currentColor` (near black): borders of the editor,
>   its dialogs and the data matching pages got an explicit `border-gray-200`.
> - `color="warn"` has no effect with the Material 3 prebuilt theme: a global rule in
>   `styles.css` makes those buttons red (data matching pages included).
> - The ontology choice of `create-entity` uses wrapping toggle buttons rather than a segmented
>   control (eight options do not fit on one line).
> - B3: the file viewer button is shown for any literal that looks like a path (the
>   `identifier`-only restriction was dropped, as proposed).

Decisions taken with the user (2026-10-17):

| # | Decision |
|---|---|
| D1 | Display additions accepted: the entity's name as the editor's title (O1), a property count in the ontology tabs (O3), snackbars harmonised with the data matching pages (O4). The Material tab group is kept and restyled (no custom tab strip). |
| D2 | The dead inline "new association" form is removed. |
| D3 | Every bug found gets a resolution (§7); they are fixed in their own commits, before the restyle. |
| D4 | The dialogs opened from the editor are restyled too (§6). |

Work is split into commits that can be reviewed separately:

1. **Bug fixes** (§7) — the only commits that change behaviour.
2. **Dead code removal** (B11).
3. **Shared style pieces** (§4).
4. **Restyle of `entity-details`** (§2, §3, §5): markup, classes, Material modules, `.scss`, plus
   the three accepted display additions. No other logic change: every `(event)` binding,
   `[(ngModel)]` and service call keeps its expression.
5. **Restyle of the dialogs** (§6), same rule.

---

## 1. The reference style (what the data matching pages use)

| Element | Data matching pages | Where |
|---|---|---|
| Page / panel | `bg-white rounded-xl border p-4`; page background `bg-gray-50` | `data-matching.component.ts`, `matching-settings.component.ts` |
| Title | `text-lg font-semibold` (panel / dialog), `text-xl font-semibold` (page) | review panel header |
| Section label | `text-xs uppercase tracking-wide text-gray-500` | "Source pairs", "Global defaults", "Compared concepts" |
| Secondary text | `text-xs text-gray-500`; IRIs `text-xs text-gray-400 break-all` or `truncate` | comparison dialog header |
| Primary button | `mat-flat-button color="primary"` | Run matching, Save, Link |
| Secondary button | `mat-stroked-button` (`color="warn"` when destructive) | Reject, Discard, Reset |
| Tertiary / link | `mat-button`, or a `text-blue-700 text-sm` text button | "← Data sources", "+ New" |
| Row actions | `.act` icon buttons (1.75 rem, rounded, glyphs ✓ ✗ ↺ ⇆ ⚙ ×) with `matTooltip` | review panel rows |
| Badges | `px-1 rounded text-[10px]` coloured badge (blue, emerald, red, sky, gray, amber); source badge `px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-xs` | review panel, comparison dialog |
| Property sheet | two-column grid, label column + values, rows separated by `border-t border-gray-100` | comparison dialog |
| Form fields | `mat-form-field appearance="outline" subscriptSizing="dynamic"` with `matInput` / `mat-select`; compact native `input` / `select` (`px-2 py-1 border rounded text-sm`) inside tables | settings, manual link dialog |
| Chips | `mat-chip-grid` / `mat-chip-row`, removable with `×` (`matChipRemove`), greyed when not editable | `iri-chip-input` |
| Toggle group | segmented control (Form / JSON switch): `inline-flex rounded-lg border overflow-hidden text-sm`, active `bg-blue-600 text-white` | settings toolbar |
| Warnings / errors | `p-2 rounded bg-amber-50 border border-amber-200 text-amber-800 text-xs` (`⚠ …`); errors `text-red-700` (`✕ …`) | blocking reasons, validation |
| Loading | `mat-progress-bar mode="indeterminate"` (lists), `mat-spinner diameter="32"` (dialog content) | review panel, comparison dialog |
| Empty state | `text-xs text-gray-500 italic` | evidence, lists |
| Dialog skeleton | `mat-dialog-title` / `mat-dialog-content` / `mat-dialog-actions align="end"`, Cancel = `mat-button`, confirm = `mat-flat-button color="primary"` (`color="warn"` for deletions) | all data matching dialogs |
| Snackbar | `snackBar.open(message, 'Close', { duration })`, default position (bottom centre), no colour class | `MatchingActionsService` |

## 2. `entity-details`: current layout and target

The dialog keeps its size (95 vw × 80 vh, opened by `EntityDetailsDialogService`).

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ←  Marie Lefebvre                                                       ×   │  header (D1/O1)
│    https://demo.local/Person/5f…  ⧉                                          │
├──────────────────────────────────────────────────────────────────────────────┤
│ SAME AS   (Marie Lefevre  gramps) (Marie Lefèvre  archive)                   │  only if sameAs
│ TYPES     (rico:Person ×) (foaf:Person 🔒 gramps)                     [+ Type]│
├──────────────────────────────────────────────────────────────────────────────┤
│ RiC-O 12 │ FOAF 3 │ SKOS │ …                                                 │  restyled mat-tab-group (D1/O3)
├──────────────────────────────────────────────────────────────────────────────┤
│ NAME                                                                Required │
│ │ Name         │ Marie Lefebvre                                   ✎  ×   │ │
│ MAIN PROPERTIES                                                              │
│ │ Birth date   │ 1948                                             ✎  ×   │ │
│ │ min 1 · max 1│                                                   [+]   │ │
│ │ Birth place  │ • Paris                                             ×   │ │
│ │ rico:Place   │ [Add a value…               ▾]                [Create]  │ │
│ OTHER PROPERTIES                                                             │
│ │ Note         │ "…"                                     🔒 tropy        │ │
│                                                      [+ Add property]        │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                           [Delete entity]    │  footer
└──────────────────────────────────────────────────────────────────────────────┘
```

| Region (current lines of `entity-details.component.html`) | Today | Target |
|---|---|---|
| Header (4–28) | Back / static title "Entity Details Form" / close, `text-2xl text-gray-500` | Dialog-title row: back `←` button (same `[class.invisible]`, same handler), **title = entity name** (D1/O1, §5), IRI on a second line `text-xs text-gray-400 truncate` with a `.act` ⧉ copy button (same `copyToClipboard`); close `×` on the right. |
| Same as (31–44) | emerald pill row | Section label `SAME AS` + one button per target with the source-badge style; same `changeSelectedEntity(s.iri)`. |
| Type chips (46–79) | `mat-chip-grid` with inline CSS variables, `mat-icon cancel`, round blue `+` | Section label `TYPES`; `mat-chip-row` with the `iri-chip-input` look (no inline style); `×` only when `type.source === 'internal'` (same condition, same `removeChip`); external types greyed with a lock and their source badge; `+ Type` as `mat-stroked-button` (same `openCreateEntityDialog()`). |
| Loading (81–85) | large spinner + text | `mat-spinner diameter="32"` centred + `text-sm text-gray-500` "Loading…", same `*ngIf`. |
| Tabs (87–88) | `mat-tab-group` | Kept, same `(selectedIndexChange)`. Restyled in the `.scss` to look like the review panel's text tab strip (thin blue ink bar, `text-sm`, inactive grey); **the label shows the number of properties of the entity in that ontology** (D1/O3, §5). |
| Name (94–171) | centred blue title, "Required" in green/red, card per value | Section label `NAME` with a right-aligned `Required` badge (emerald when filled, red otherwise — same `[ngClass]` condition); then a property-sheet row (§3). |
| Main properties (173–375) | centred title, uppercase labels, "Min cardinality is : 1" in green/red, card per value, round blue "+" | Section label `MAIN PROPERTIES`; one property-sheet row per property (§3); cardinality as a compact `min 1 · max 1` line under the label, same colour conditions. |
| Other properties (377–578) | same as main | Section label `OTHER PROPERTIES` (same "Other " condition); property-sheet rows. |
| Add property (581–594) | blue text button centred | `mat-stroked-button` "+ Add property" right-aligned under the sheet (same `addAssociation()`). |
| Inline "new association" form (596–736) | never displayed | **Removed** (D2, B11). |
| Entity IRI (739–757) | card with copy button | Moved to the header (second line). |
| Danger zone (759–767) | full-width red outlined button | Footer: `mat-stroked-button color="warn"` "Delete entity", right-aligned (same `deleteEntity()`). |

## 3. The property-sheet row, and one shared template for values

Same structure as the comparison dialog rows:

```html
<div class="grid grid-cols-[12rem_1fr] gap-3 py-2 border-t border-gray-100 group">
  <div class="min-w-0">                                   <!-- label column -->
    <div class="text-sm font-medium text-gray-800">{{ label }}</div>
    <div class="text-xs text-gray-400 truncate">{{ range local name }}</div>
    <div class="text-[11px]"><span [ngClass]="…">min {{ min }}</span> · <span [ngClass]="…">max {{ max }}</span></div>
  </div>
  <div class="min-w-0 space-y-1">                         <!-- values column -->
    <ng-container *ngTemplateOutlet="valueList; context: { $implicit: property }"></ng-container>
  </div>
</div>
```

**Today the value list is written three times** (name, main properties, other properties) with
small differences, which is exactly where bugs B1, B7 and B10 come from. The restyle defines it
**once**, as an `<ng-template #valueList let-property>`, used by the three sections. The
template carries the corrected expressions of §7 (so B1/B7/B10 cannot come back).

| Kind | Read | Edit / add |
|---|---|---|
| literal, internal | text `text-sm truncate` with `[title]` = the value; `.act` ✎ (`toggleLiteralEdit`) and `.act` × (`onRangeDeleted`), shown on row hover (`group-hover`); `.act` 👁 for a file path (B3) | native `input` `w-full text-sm px-2 py-1 border rounded` (same `[(ngModel)]`, Enter/Escape); ✎ becomes ✓ while editing |
| literal, external | text + lock badge `px-1 rounded text-[10px] bg-gray-100 text-gray-600` with the source name | — |
| iri | `•` + link `text-blue-700 hover:underline` (`changeSelectedEntity(propValue.value)`), label = name or IRI (B7), × for internal values | native `select` `text-sm px-2 py-1 border rounded` with its `optgroup`s + `mat-stroked-button` "Create" |
| add literal | `.act` "+" with tooltip "Add a value" (same `addNewDataType`, same `*ngIf`) | |

Native `input`/`select` are kept on purpose: `mat-select` would change the event signatures
(`onRangeSelected` reads `event.target.value`). The settings page uses native selects in its
concept table as well.

## 4. Shared pieces

1. **`.act` icon buttons** — today scoped in `matching-review-panel` (`styles: [...]`). Moved to
   `styles.css` (same name); the review panel uses the global one.
2. **`section-label`** class in `styles.css` (`text-xs uppercase tracking-wide text-gray-500`).
3. **`shared/ui/source-badge.component.ts`** —
   `<app-source-badge [source]="'internal'|'external'" [name]="datasourceShortName">`, used by the
   editor (values, types, same-as). Adopting it in the data matching dialogs is a later clean-up.
4. **`shared/ui/confirm-dialog.component.ts`** — one confirmation dialog (title, message, details
   as label/value pairs, confirm label, `warn` flag), replacing the two near-identical
   `confirm-delete-*` dialogs and the data matching `simple-confirm-dialog` (§6). The
   `ConfirmDeleteDialogData` / `ConfirmDeletePropertyData` interfaces stay as aliases so the
   callers do not change.

## 5. The accepted display additions (D1)

- **O1 — title.** A getter `displayName` in `EntityDetailsComponent`: the first non-empty value
  of the RiC-O `nameProperty` (already computed by `extractNameProperty`), else the first value
  of another label predicate present on the entity, else the IRI. Also used by B8.
- **O3 — tab count.** A method `propertyCount(entry)` = number of `entities` + properties of
  `main_Properties` that have at least one value + 1 if `nameProperty` has a value; shown in
  the tab label as `RiC-O 12` (count in `text-xs text-gray-400`), hidden when 0.
- **O4 — snackbars.** The editor's `snackBar.open(...)` calls (update, delete, errors) use the
  data matching form: `(message, 'Close', { duration: 4000 })`, default position, no colour
  class; messages use the entity's name rather than its IRI ("Marie Lefebvre updated."). The
  `snackbar-success` / `snackbar-error` classes are then unused in this component and removed
  from its `.scss` (they stay in `create-entity` until §6 does the same there).

## 6. The dialogs opened from the editor (D4)

All move to the dialog skeleton of §1 (`mat-dialog-title` / `mat-dialog-content` /
`mat-dialog-actions align="end"`), with the same buttons, fields and badges as the data matching
dialogs. Their logic is unchanged except for the bugs of §7 that concern them.

| Dialog | Today | Target |
|---|---|---|
| `create-entity` (types mode from the editor, range mode from "Create") | home-made header-less form; chips with `mat-icon`; `mat-button-toggle-group` for the ontology; native `select`; custom blue buttons; "Other ontologies" shows "In Developement ..." | Title "Add a type" / "New entity" (depending on the mode); `section-label`s ("Ontology", "Type", "Name"); chips in the `iri-chip-input` look with `×` only for internal types (B16); ontology choice as the **segmented control** of the settings page; native `select` restyled; name as `mat-form-field` outline; "Other ontologies" kept but its panel shows an `italic text-gray-500` note "Not available yet" instead of "In Developement ..."; actions Cancel (`mat-button`) + Create / Save (`mat-flat-button color="primary"`, B15 for the disabled state). |
| `rico-properties` (Add property) | centred blue title, `mat-chip-listbox` filters, `mat-divider`s, radio list with `matBadge` iri/literal, no Cancel | Title "Add a property"; `section-label` "Value" with the filters as chips (Literal + main types, single selection, B18) and the "Other types" native `select`; list of predicates as clickable rows (label + CURIE in `text-xs text-gray-400` + badge `iri`/`literal` in the §1 badge style) — a click returns the predicate (same `onCheckboxChange(prop)`); empty state "No property for this filter."; Cancel button (`mat-dialog-close`). Drops `MatRadioModule`, `MatBadgeModule`, `MatDividerModule`. |
| `range-selection-dialog` | already close to the target | Title "Choose a type", rows as in the manual link dialog list (`hover:bg-blue-50`, local name + IRI in `text-xs text-gray-400`), Cancel `mat-button`. |
| `confirm-delete-dialog`, `confirm-delete-property` | two copies of a red card dialog, French "Annuler" | Both replaced by the shared `confirm-dialog` (§4): title "Delete this entity?" / "Delete this value?", details (Name / Property / Value / IRI) as `section-label` + value lines, actions Cancel (`mat-button`) + Delete (`mat-flat-button color="warn"`). The unused `.html` files of the old components ("…works!") are deleted with them. |
| `file-viewer` | bare buttons, grey path box | Title "File"; path as `text-xs text-gray-400 break-all`; preview area `rounded-xl border` with the empty state style; actions Select file… (`mat-stroked-button`), Cancel (`mat-button`), Use this file (`mat-flat-button color="primary"`, only in dialog mode as today). Outside Electron, a notice "Selecting a file needs the desktop application." (B20). |

## 7. Bugs and their resolution

Fixed before the restyle, one commit per group (editor / dialogs). Each fix lists how it is
checked.

### Entity editor

**B1 — Links in "Other properties" do not navigate.**
*Cause:* html 438 calls `changeSelectedEntity(propValue.iri)`; the values (`RdfValueDto`) have
`value`, not `iri`, so the call receives `undefined` and does nothing. The **Main properties**
section does it right (html 236): `changeSelectedEntity(propValue.value)`.
*Resolution:* use the same expression as Main properties, `changeSelectedEntity(propValue.value)`.
The restyle then writes the IRI value list once (§3), from the Main properties version, so the
two sections cannot diverge again.
*Check:* click a linked entity in Other properties → the editor shows it, ← comes back.

**B2 — Internal types outside the configured ontologies are deleted at the first save.**
*Cause:* `buildTypesChips` (ts 320–339) creates a chip only for a type whose IRI starts with a
configured ontology namespace; `editEntity` (ts 834–837) sends the internal chips as **the
complete list** of types, and the backend (`RdfEntityService.updateByKey`) replaces all internal
`rdf:type` triples with it. A type matching two namespaces also gets two chips.
*Resolution:* `buildTypesChips` creates one chip per type: the first matching namespace gives the
label `name:LocalName` (then `break`); otherwise the label is the local name and the chip keeps
`ontology: null` (shown with its full IRI as tooltip). `editEntity` is unchanged: it now sends
every internal type back.
*Check:* add `ex:Thing` (unknown namespace) as internal type with SPARQL, edit a literal in the
editor, reload: the type is still there; no duplicate chip.

**B3 — The file viewer is unreachable, and its result is not saved.**
*Cause:* the "eye" tests `isFilePath(property.schema.value)` (html 190) and
`isFilePath(property.value)` (html 392): both are undefined, the paths are in
`property.values[i].value`. `openFileViewer` writes the chosen path to `property.value`
(ts 1154) and does not save.
*Resolution:* the eye moves to the value line: `*ngIf="isFilePath(propValue.value)"`,
`(click)="openFileViewer(propValue, propValue.value)"`, for any literal that looks like a path
(the `identifier`-only restriction is dropped: it hid the feature on other properties; tell me
if it must stay). In `openFileViewer`, the result is written to that value
(`propValue.value = newFilePath`) and saved with `editEntity()` when the value is internal; for
an external value the viewer is read-only (no "Use this file" button, §6).
*Check:* an `rico:identifier` holding `/Users/…/photo.jpg` shows 👁; picking another file in
Electron updates and saves the value.

**B4 — Deleting a new empty value throws.**
*Cause:* `onRangeDeleted` (ts 213) calls `rangeValue.trim()`; a value created by "Add property"
or the empty Name has `value: null` → `TypeError`, the value stays.
*Resolution:* `const rangeValue = range?.value ?? ''`; and remove the value **by identity**
(`property.values.filter(v => v !== range)`) instead of by equal text, which also stops deleting
a same-text value of another source from the display.
*Check:* Add property → × on the empty value removes it; deleting one of two equal texts removes
only that one.

**B5 — A value picked in a range list cannot be removed until reload.**
*Cause:* `onRangeSelected` (ts 171–176) builds the new value without `source` (nor `kind`), and
the × is shown only for `source === 'internal'`.
*Resolution:* add `kind: 'iri', source: 'internal', datasourceShortName: 'internal'` to the new
value. (B14 reuses the same helper `addIriValue(property, iri, name)`.)
*Check:* pick a value → its × is there immediately.

**B6 — Range lists show another property's entities.**
*Cause:* all range `select`s share `groupedRanges` / `listOfAllRanges` (ts 68–71), loaded
asynchronously on `(click)` (html 259–267, 463–472): the list that opens shows the previously
loaded entities until the request returns. `getEntitiesByTypes` (ts 1013) does not reset
`lastLoadedRangeUri`, so `getEntitiesByType` (ts 1035) may return early with another
property's list; `onRangeSelected` then looks the pick up in that list.
*Resolution:* a per-range-set cache, `rangeOptions: Map<string, RangeOptions>` keyed by the
sorted range IRIs (`{ loading, groups, all }`). When an entity is shown or a tab changes, the
options of every IRI property of that tab are loaded in advance (one request per distinct
range set, through the existing `getAllEntitiesByType(uris, true)`), so a list is filled
before the user opens it; `(focus)` loads it if it is still missing. Each `select` iterates
its own entry; while loading it shows a disabled "Loading…" option. `onRangeSelected` looks the
pick up in that entry. The cache is cleared when the entity changes and when an entity is
created from "Create" (B14). `listOfAllRanges`, `groupedRanges`, `lastLoadedRangeUri` and the
two `getEntitiesBy…` methods are replaced by `loadRangeOptions(property)`.
*Check:* two IRI properties with different ranges: each list shows only its own types, at the
first opening.

**B7 — Linked entities without a name are hidden or blank.**
*Cause:* the link is rendered only `*ngIf="propValue.name && propValue.name !== ''"` (html 235,
431): in Other properties such a value is invisible (cannot be seen or deleted); in Main
properties it is an empty row.
*Resolution:* always render the value; label `propValue.name || propValue.value` (the IRI in
`italic text-gray-500` when there is no name).
*Check:* link to an entity without `rico:name` → the IRI is shown, clickable and removable.

**B8 — The delete-entity confirmation shows no name.**
*Cause:* ts 766 passes `this.selectedEntity.titre`, which does not exist.
*Resolution:* pass `this.displayName` (§5).

**B9 — The delete-value confirmation shows no property name for main properties.**
*Cause:* ts 220 passes `property.key`, undefined for main-property placeholders.
*Resolution:* `property.key ?? property.schema?.label ?? extractPropertyNameFromIRI(property.predicate)`;
and for an IRI value pass its name (`range.name || rangeValue`) rather than the bare IRI.

**B10 — No tooltip on truncated literal values.**
*Cause:* `[title]="property.value"` (html 295, 500) is undefined; html 115 does it right with
`propValue.value`.
*Resolution:* `[title]="propValue.value"` (in the shared value template).

**B11 — Dead code: the inline "new association" form (D2).**
*Cause:* `addAssociation()` opens the `RicoPropertiesComponent` dialog and never sets
`newAssociation`; `confirmAddAssociation()` would only push to `entityPropertiesDict`, which is
never saved; its ontology `option`s have `[value]=""`.
*Resolution:* remove html 596–736, the `newAssociation` field, `confirmAddAssociation`,
`cancelAddAssociation`, `entityPropertiesDict`; also the other dead members `removeAssociation`,
`seeProperty`, `@Output() close`, and the unused imports (`Entity`, `FormBuilder`, `FormGroup`,
`FormArray`, `Validators`, `ReactiveFormsModule`, `ONTOLOGY_LABELS`, `Input`, `OnChanges`,
`SimpleChanges`), after checking each with a search.

**B12 — Empty literals are saved.**
*Cause:* "+" adds `value: ''`; confirming it empty calls `editEntity`, which drops only `null`
values (ts 815): an empty string literal is stored.
*Resolution:* in `editEntity`, also drop literal values whose text is blank; in
`saveLiteralEdit`, a blank value is removed from the list instead of being kept.

**B13 — "Copy IRI" gives no feedback.**
*Resolution:* snackbar "IRI copied." (O4 style) on success, the error message otherwise.

**B14 — An entity created with "Create" next to a range list is not linked to the property.**
*Cause:* `openCreateEntityDialogWithType` (ts 281–289) ignores the dialog's result; the new
entity must then be found again in the list (which, with B6, may not even contain it).
*Resolution:* subscribe to `afterClosed()`; the dialog returns the created `RdfEntityDto`
(`POST /rdf/entities` returns it, `create-entity` closes with it): add it with
`addIriValue(property, res.iri, <its name>)` (B5), clear the range cache (B6), and save with
`editEntity()`. `openCreateEntityDialogForRange` passes the property along to do so.
*Check:* "Create" → name → Create: the new entity appears as the property's value, and in the
list.

### Dialogs

**B15 — `create-entity` (range mode) creates an entity without a name.**
*Cause:* the "Create" button of the `fullType` mode has no `[disabled]`.
*Resolution:* `[disabled]="!entityForm.get('propertyValue')?.value?.trim()"` (and the same
condition for the other Create button, instead of `entityForm.invalid`, which also requires a
type picked in the select even when the type is given).

**B16 — `create-entity` (types mode) lets the user remove external types.**
*Cause:* the chips' `×` is shown for every type; removing an external type has no effect on
save (only internal types are written) and it comes back on reload.
*Resolution:* `×` only when `selectedType.source !== 'external'`; external chips greyed with
their source badge (§6).

**B17 — "Add property" breaks in five ontology tabs.**
*Cause:* `rico-properties` does `this.removeProperty.includes(...)` (ngOnInit); `removeProperty`
comes from `configuration.json`, where only RiC-O and FOAF define it (SKOS, PNV, BIO, BIBO,
BIBFRAME do not) → `TypeError`, the dialog opens with an empty list.
*Resolution:* `this.removeProperty = data.removeProperty ?? []` and
`this.predefinedRanges = data.predefinedRanges ?? []` in the dialog; in the editor,
`mainTypes?.value ?? []`.
*Check:* "Add property" in the SKOS tab of an entity typed `skos:Concept` lists its properties.

**B18 — `rico-properties` filters are inconsistent.**
*Cause:* the "Literal" chip filters from `this.predicates` instead of
`fixedFilteredPredicates`, so the properties removed by `removeProperty` come back; it is
handled twice (`(click)` and `(selectionChange)`); deselecting a chip fires `selectionChange`,
which applies the filter again instead of removing it.
*Resolution:* one handler on the listbox, `(change)="onFilterChange($event.value)"`, that
recomputes `filteredPredicates` from `fixedFilteredPredicates`: no value → all; `'literal'` →
data properties; a type IRI → object properties with that range. The "Other types" select
calls the same handler.

**B19 — `rico-properties` has no Cancel button.**
*Resolution:* `mat-dialog-actions` with a Cancel (`mat-dialog-close`) — part of the restyle.

**B20 — `file-viewer`: silent outside Electron, `.ogg` always a video.**
*Resolution:* when `window.electronAPI` is missing, show "Selecting a file needs the desktop
application." and hide "Select file…"; `.ogg`/`.oga` → audio, `.ogv` → video.

## 8. Steps and verification

1. Bug fixes, editor (B1–B10, B12–B14), then dialogs (B15–B20); each with the check above.
2. Dead code removal (B11).
3. Shared pieces (§4): global `.act` and `section-label`, `source-badge`, `confirm-dialog`.
4. `entity-details` restyle (§2, §3) and display additions (§5).
5. Dialogs restyle (§6).
6. For every step:
   - `ng build` without warning;
   - for the restyle steps, a binding audit: every `(click)`, `(change)`, `(keydown.*)`,
     `[(ngModel)]`, `*ngIf`, `*ngFor` of the old template is found in the new one with the same
     expression, except those changed by §7 (script comparing both versions);
   - screenshots (headless Chrome) on the demo project: internal entity, external read-only
     entity, entity with `sameAs`, entity with empty main properties, each dialog;
   - manual pass: edit a literal (Enter, Escape, ✓), delete a value, add a value, pick a range
     value, Create a range entity, add/remove a type, add a property (RiC-O and SKOS tabs),
     open a file, back/close, delete the entity.
7. `CLAUDE.md` (frontend section: shared `ui/` pieces, `confirm-dialog`) updated at the end.
