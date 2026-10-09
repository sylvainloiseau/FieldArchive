# Fix: external data sources never record their file location, so re-import fails

## Context

Re-importing ("syncing") an *ExternalDataSource* is a core feature: the user exports data from a third-party tool (Tropy, Lameta, Gramps) and re-imports it into FieldArchive, replacing that source's named graph. Today it always fails for sources created through the UI, with the opaque message *"Graphe ou chemin du fichier manquant"*. Four defects compound along one chain:

1. **The path travels under the wrong name.** `gestion-sources.component.ts:105-110` captures the chosen file's path into a form control called `url`; `data-source-http.service.ts:93-100` ships the raw form value as the `dataSource` JSON part. The backend DTO `CreateExternalDataSourceRequest` declares `sourceLocation`, so Jackson silently drops `url` and `getSourceLocation()` is null.
2. **The backend never writes it anyway.** `DataSourceService.createExternalDataSource:399-402` has the write commented out — and the commented code calls `req.getUrl()`, which does not exist on the DTO, so uncommenting it verbatim would not even compile.
3. **Sync reads it back and rejects.** `synchronizeExternalDataSource:470-477` requires `app:sourceLocation` and throws `BadRequestException` when it is absent, which is always.
4. **The real error is masked.** The blanket `catch (Exception e) { throw new RuntimeException(...) }` at `:530-532` swallows that deliberate `BadRequestException` (and `NotFoundException`), so `ApiExceptionHandler` never maps it and the UI receives a generic 500 instead of a 400 carrying the actual reason.

### Two independent constraints on path-based sync

Worth separating, because they have different remedies:

- **Reading the file.** Sync does `Files.newInputStream(Path.of(location))`, so the path must resolve on the **backend's** filesystem. Whenever backend and file sit on the same machine — the Electron product by construction, but equally a browser against a locally-run backend — this holds. Only a genuinely remote backend breaks it.
- **Learning the path.** A browser-sandbox limit, not a machine-location one: a plain browser's `<input type="file">` never exposes an absolute path to JavaScript (`File.name` is the basename; `input.value` yields the fake `C:\fakepath\…`). Inside Electron a path *is* obtainable — the current code uses `File.path` (`:109`), which Electron removed in v32. The supported successor, **`webUtils.getPathForFile(file)`, is already present in the pinned Electron 29.4.6** (`node_modules/electron/electron.d.ts:14964-14977`: *"This method superceded the previous augmentation to the `File` object with the `path` property."*), so the capture can be made future-proof now, with no upgrade.

So: auto-fill the path in Electron via `webUtils`; make the field **user-editable** (it is `readonly` today at `gestion-sources.component.html:373-379`, so a same-machine browser user cannot even type one in); and fall back to uploading the file's bytes when no usable path exists — which also covers a remote backend.

## Electron changes

**`electron/electron-preload.js`** — bridge the supported API alongside the existing `selectFile`:
```js
const { contextBridge, ipcRenderer, webUtils } = require('electron');
contextBridge.exposeInMainWorld('electronAPI', {
  /* … existing members … */
  getPathForFile: (file) => webUtils.getPathForFile(file),
});
```
`contextIsolation: true` / `nodeIntegration: false` (`electron/main.js:113-118`) means the renderer cannot `require('electron')`, so the call must live in preload — this is the pattern Electron's own docs use, and it accepts a `File` passed across the bridge.

**`Frontend/src/types/electron.d.ts`** — declare the new member, and make `electronAPI` itself optional so browser mode type-checks honestly:
```ts
electronAPI?: {
  selectFile: () => Promise<string | null>;
  getPathForFile?: (file: File) => string;
};
```

## Backend changes

**`Backend/src/main/java/fr/cnrs/lacito/fieldarchive/services/DataSourceService.java`**

- Add a private helper beside the other small helpers, extracting the `file://` stripping currently inlined at `:496-501` so the write and read paths agree on one canonical form:
  ```java
  /** Trims, strips a file:// prefix, returns null when nothing usable is left. */
  private String normalizeFileLocation(String raw) { ... }
  ```
- `createExternalDataSource` (`:369`): replace the commented block at `:399-402` with a real write, using the getter that actually exists:
  ```java
  String sourceLocation = normalizeFileLocation(req.getSourceLocation());
  if (sourceLocation != null) {
      conn.add(ds, pSourceLocation(), vf.createLiteral(sourceLocation), ctxMeta);
  }
  ```
  Absence stays non-fatal: the source is still created and the uploaded bytes still imported; it just isn't syncable by path until a location is supplied.
- Change the sync signature to `synchronizeExternalDataSource(String shortName, MultipartFile uploadedFile, String newSourceLocation)`, keeping the existing existence/`external` checks and graph lookup (`:456-479`), then choosing the input:
  - **uploaded file present** → import from `uploadedFile.getInputStream()`, and when `newSourceLocation` is non-blank, upsert `app:sourceLocation` (remove-then-add, as `updateDataSource` does) so later syncs become one-click;
  - **otherwise** → read the stored `sourceLocation`; if null, throw `BadRequestException("No file location recorded for this source. Set its location or re-import by selecting the file.")`; if the file is gone, `BadRequestException("RDF file not found: " + path)`.
  - Feed either stream into one private helper doing `begin / clear(ctxGraph) / add(in, "", RDFFormat.TURTLE, ctxGraph) / refresh app:lastSync / commit`, mirroring `:482-528`. Keeping clear+import in a single transaction matters: a parse failure must roll back rather than leave the source empty.
- **Delete the blanket catch at `:530-532`.** Let `BadRequestException`/`NotFoundException` reach `ApiExceptionHandler` (400/404 with `{"error": ...}`); wrap only genuinely unexpected IO/parse failures in a message naming the file. This is what lets the frontend distinguish "no location recorded" from a server fault — the fallback UX depends on it.
- `updateDataSource` (`:286`): also accept and upsert `sourceLocation`, so a user who moves the file can repoint the source without recreating it. Requires adding the field to `UpdateDataSourceRequest` (currently `longName` + `description` only).

**`Backend/src/main/java/fr/cnrs/lacito/fieldarchive/controllers/DataSourceController.java`**

- `syncExternal` accepts the optional upload:
  ```java
  @PostMapping("/{shortName}/sync")
  public ResponseEntity<?> syncExternal(
          @PathVariable String shortName,
          @RequestParam(value = "file", required = false) MultipartFile file,
          @RequestParam(value = "sourceLocation", required = false) String sourceLocation) {
      dataSourceService.synchronizeExternalDataSource(shortName, file, sourceLocation);
      return ResponseEntity.ok(Map.of("status", "synchronized"));
  }
  ```
  `@RequestParam(required = false)` rather than `@RequestPart` keeps the existing body-less POST working while also binding a multipart upload (same style as `ProjectController.importBackup`).

## Frontend changes

**`Frontend/src/app/services/data-source-http.service.ts`**

- `createExternalSource` (`:87`): map the form value onto the backend DTO instead of `JSON.stringify`-ing it raw — the correct mapping already sits commented out at `:63-82`; reinstate it with `sourceLocation: data.dataSource.url || ''`.
- `syncExternalSource` (`:159`): take optional `file?: File` and `sourceLocation?: string`; when a file is given, POST `FormData` carrying `file` (+ `sourceLocation` when known), otherwise keep today's empty POST.
- `updateDataSource` (`:120`): include `sourceLocation: data.url` so an edited path is persisted.

**`Frontend/src/app/components/gestion-sources/gestion-sources.component.ts` / `.html`**

- `onFileSelected` (`:100`): replace `this.selectedFile.path` with `window.electronAPI?.getPathForFile?.(file)`, and only patch `url` when a path actually comes back. With the bridge typed, `selectedFile` (`:70`) can drop its `| any` escape hatch and be plain `File | null`.
- **Drop `readonly` from the location input** (`html:373-379`) and relabel it "File path (on the machine running FieldArchive)", so a same-machine browser user can supply one by hand and get one-click sync.
- Relax the `onSubmit` guard at `:234-239` that hard-rejects creation without a path — the uploaded bytes alone are enough to create a source.
- `reimportSource` (`:358`): if the source has a location, sync as today; otherwise offer the `.ttl` picker and sync with the chosen file plus its captured path. If a sync 400s because the recorded file is missing, offer the same picker/path field as a retry. Also stop interpolating a possibly-undefined path into the confirm dialog (currently renders "Re-import from undefined?").
- Allow external sources into the edit form (`openEditForm:194-211` currently refuses anything with `editable === false`) at least for name/description/location.

## Recommended companion fix (same user flow)

`@ViewChild('fileInput')` (component `:72`) resolves to the **first** `#fileInput` in the view — the `.json` configuration-import input at `gestion-sources.component.html:98-103` — not the `.ttl` one at `:352`. So the green "Import" button opens a `.json`-filtered picker, and the config-import button shares `(change)="onFileSelected($event)"`, clobbering `selectedFile`/`url`. Give the two inputs distinct refs (`#rdfFileInput` / `#configFileInput`) and separate handlers.

## Out of scope (flagged, not fixed)

- **Remote `http(s)://` locations.** The field is named `url` and an earlier placeholder promised `https://example.com/data.rdf`, but sync only ever opens a filesystem path.
- `sourceTool`/`tool` is never captured or stored (no form control, no DTO field), so the table's "Source" column always shows `-`.
- `isFileLoading` is set at component `:106` and never reset to `false`.
- `exportAsCSV()` lists a `Tool` header but omits the value, so CSV columns are off by one.
- Both import paths hardcode `RDFFormat.TURTLE`; `.rdf`/`.owl` exports will not parse.

## Verification

1. `cd Backend && mvn -q compile` and `cd Frontend && npx tsc -p tsconfig.app.json --noEmit`.
2. **Electron, auto-filled path:** `npm run build:all && npx electron .` (or dev mode); create an external source from a `.ttl` and confirm `GET http://localhost:8080/datasources` shows a non-null `sourceLocation` — proving `webUtils.getPathForFile` returned a real path through the bridge. Edit the file externally, click re-import, confirm graph content changes and `lastSync` advances.
3. **Browser, same machine, typed path:** with `ng serve` + a locally-run backend, create a source and type the absolute path into the now-editable field; confirm one-click re-import works — impossible with the current code.
4. **No path at all:** create a source without a location; creation succeeds, re-import prompts for the file, replaces the graph, and (if a path was supplied) makes the next sync one-click.
5. **Error surfacing:** `curl -X POST http://localhost:8080/datasources/<name>/sync` for a source with no recorded location must return **400** with `{"error":"No file location recorded..."}`, not a 500.
6. Regression: a source that already has a location still syncs via the body-less POST, confirming the controller change is backward compatible.
