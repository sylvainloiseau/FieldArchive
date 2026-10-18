import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormControl, FormGroup, Validators } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
 
import { DataSourceHttpService } from '../../services/data-source-http.service';
import { DataSource, CreateInternalDataSourceRequest, CreateExternalDataSourceRequest, ImporterSettings } from '../../models/data-source.model';
import { ImporterHttpService } from '../../services/importer-http.service';
import { Importer, ImporterOption, Importers, ImportResult, PluginProblem, RDF_FILE_IMPORTER } from '../../models/importer.model';
 
// Angular Material Imports
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
//import { MatMenuModule } from '@angular/material/menu';
 
@Component({
  selector: 'app-gestion-sources',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatTableModule,
    MatDialogModule,
    MatSnackBarModule,
    MatChipsModule,
    MatTooltipModule
    //MatMenuModule
  ],
  templateUrl: './gestion-sources.component.html',
  styleUrl: './gestion-sources.component.scss'
})
export class GestionSourcesComponent implements OnInit, OnDestroy {
  dataSources: DataSource[] = [];
  selectedDataSource: DataSource | null = null;
  expandedHistory: string | null = null;
  
  showForm = false;
  isEditing = false;
  isFileLoading = false;

  currentEditId: string | null = null;
  
  dataSourceForm: FormGroup;
  private destroy$ = new Subject<void>();

  //Props pour les filtres
  showFilterMenu = false;
  filterType: 'ALL' | 'INTERNAL' | 'EXTERNAL' = 'ALL';
  filterTool: string = 'ALL';
  searchTerm: string = '';

  //props pour les stats
  showExportMenu = false;
  selectedFile: File | null = null;

  // The source awaiting a file for re-import, set while the picker is open.
  private pendingReimport: DataSource | null = null;

  /** Accepted by the RDF file pickers: every format the backend parses, chosen by extension. */
  readonly rdfFileAccept = '.ttl,.turtle,.rdf,.xml,.owl,.nt,.nq,.trig,.trix,.n3,.jsonld,.rj,.brf';

  // Importers (GET /importers): "Sync with an RDF file" and the plugins
  importers: Importers | null = null;
  /** The edited source's stored importer when no plugin has it (the drop-down is then disabled). */
  missingImporter: string | null = null;
  /** The last import run from the open form, with its warnings. */
  lastResult: ImportResult | null = null;
  showResultWarnings = false;
  readonly canUseElectronDialogs = !!window.electronAPI;

  // Three distinct hidden inputs: a single #fileInput ref used to resolve to whichever
  // came first in the template (the .json one), so the wrong picker opened.
  @ViewChild('rdfFileInput') rdfFileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('configFileInput') configFileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('reimportFileInput') reimportFileInput!: ElementRef<HTMLInputElement>;

  constructor(
    private dataSourceService: DataSourceHttpService,
    private importerService: ImporterHttpService,
    private fb: FormBuilder,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    public router:Router
  ) {
    // Création du formulaire
    this.dataSourceForm = this.fb.group({
      shortName: ['', [
        Validators.required, 
        Validators.pattern(/^[a-zA-Z0-9_-]+$/)
      ]],
      name: ['', Validators.required],
      description: [''],
      sourceType: ['INTERNAL', Validators.required],
      url: [''],
      importer: [RDF_FILE_IMPORTER],
      importBaseIri: [''],
      importerOptions: new FormGroup<Record<string, FormControl>>({})
    });
    this.dataSourceForm.get('importer')!.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(id => this.buildOptionControls(id));
  }

  // ===== Importers =====

  private loadImporters(): void {
    this.importerService.load().pipe(takeUntil(this.destroy$)).subscribe({
      next: importers => {
        this.importers = importers;
        this.buildOptionControls(this.dataSourceForm.get('importer')!.value);
      },
      error: err => console.error('Cannot load the importers', err)
    });
  }

  /** The importers offered in the drop-down: the plugins, then "Sync with an RDF file". */
  importerChoices(): Importer[] {
    return this.importers?.importers ?? [];
  }

  /** The importer selected in the form (undefined while the stored one has no plugin). */
  currentImporter(): Importer | undefined {
    const id = this.dataSourceForm.get('importer')?.value;
    return this.importerChoices().find(i => i.id === id);
  }

  isPlugin(): boolean {
    const importer = this.currentImporter();
    return !!importer && importer.id !== RDF_FILE_IMPORTER;
  }

  pathLabel(): string {
    switch (this.currentImporter()?.inputKind) {
      case 'DIRECTORY': return 'Directory path (on the machine running FieldArchive)';
      case 'FILE_OR_DIRECTORY': return 'File or directory path (on the machine running FieldArchive)';
      default: return 'File path (on the machine running FieldArchive)';
    }
  }

  acceptsDirectory(): boolean {
    const kind = this.currentImporter()?.inputKind;
    return kind === 'DIRECTORY' || kind === 'FILE_OR_DIRECTORY';
  }

  acceptsFile(): boolean {
    return this.currentImporter()?.inputKind !== 'DIRECTORY';
  }

  get optionsGroup(): FormGroup {
    return this.dataSourceForm.get('importerOptions') as FormGroup;
  }

  /** Options whose default is true/false are shown as checkboxes. */
  isBooleanOption(option: ImporterOption): boolean {
    return option.defaultValue === 'true' || option.defaultValue === 'false';
  }

  /** One control per option of the selected plugin, filled with the edited source's values. */
  private buildOptionControls(importerId: string): void {
    const group = this.optionsGroup;
    Object.keys(group.controls).forEach(name => group.removeControl(name));
    const importer = this.importerChoices().find(i => i.id === importerId);
    if (!importer) return;
    const edited = this.editedSource();
    const stored = edited && edited.importer === importerId ? edited.importerOptions ?? {} : {};
    for (const option of importer.options) {
      const value = stored[option.name];
      group.addControl(option.name, new FormControl(this.isBooleanOption(option)
        ? (value ?? option.defaultValue) === 'true'
        : value ?? ''));
    }
  }

  /** The importer part of the form, for the backend. */
  importerSettings(): ImporterSettings {
    const importer = this.missingImporter ?? this.dataSourceForm.get('importer')!.value ?? RDF_FILE_IMPORTER;
    if (!this.isPlugin()) {
      return { importer };
    }
    const options: Record<string, string> = {};
    for (const option of this.currentImporter()!.options) {
      const value = this.optionsGroup.get(option.name)?.value;
      if (typeof value === 'boolean') options[option.name] = String(value);
      else if (value != null && String(value).trim() !== '') options[option.name] = String(value).trim();
    }
    return {
      importer,
      importerOptions: options,
      importBaseIri: (this.dataSourceForm.get('importBaseIri')!.value || '').trim()
    };
  }

  /** The base IRI the plugin would use if the form were imported now. */
  effectiveBaseIri(): string {
    const typed = (this.dataSourceForm.get('importBaseIri')?.value || '').trim();
    return typed || this.editedSource()?.importBaseIri || this.editedSource()?.importBaseIriDefault || '';
  }

  /** True when an already imported source would get another base IRI: every entity would be renamed. */
  baseIriChanges(): boolean {
    const source = this.editedSource();
    if (!source?.lastImport || !source.importBaseIri || !this.isPlugin()) return false;
    const typed = (this.dataSourceForm.get('importBaseIri')?.value || '').trim();
    return typed !== '' && typed !== source.importBaseIri;
  }

  pluginDirectory(): string | null {
    return this.importers?.directories.user ?? null;
  }

  pluginErrors(): PluginProblem[] {
    return (this.importers?.problems ?? []).filter(p => p.error);
  }

  fileName(path: string): string {
    return path.split(/[\\/]/).pop() || path;
  }

  async openPluginDirectory(): Promise<void> {
    const dir = this.pluginDirectory();
    if (!dir) {
      this.showNotification('The plugin directory is unknown (FIELD_ARCHIVE_DATA is not set).', 'error');
      return;
    }
    const open = window.electronAPI?.openDirectory;
    if (!open) {
      this.copyToClipboard(dir);
      this.showNotification(`Plugin directory (copied): ${dir}`, 'info');
      return;
    }
    const error = await open(dir);
    if (error) this.showNotification(error, 'error');
  }

  /** Scans the plugin directories again; a plugin dropped there becomes available. */
  rescanPlugins(): void {
    this.importerService.rescan().pipe(takeUntil(this.destroy$)).subscribe({
      next: importers => {
        this.importers = importers;
        const found = this.missingImporter
          ? importers.importers.find(i => i.id.toLowerCase() === this.missingImporter!.toLowerCase())
          : undefined;
        if (found) {
          this.missingImporter = null;
          const control = this.dataSourceForm.get('importer')!;
          control.enable();
          control.setValue(found.id);
          this.showNotification(`Plugin "${found.id}" found.`, 'success');
        } else {
          this.buildOptionControls(this.dataSourceForm.get('importer')!.value);
          this.showNotification(`${importers.importers.length - 1} plugin(s) loaded.`, 'info');
        }
        this.loadDataSources();
      },
      error: err => this.showNotification(`Error: ${err.message}`, 'error')
    });
  }

  /** Replaces the stored importer without plugin by "Sync with an RDF file" (saved by Update or Import). */
  turnToRawRdf(): void {
    this.missingImporter = null;
    const control = this.dataSourceForm.get('importer')!;
    control.enable();
    control.setValue(RDF_FILE_IMPORTER);
  }

  /** Picks the path a plugin reads (Electron dialogs); when editing, offers to import it right away. */
  async choosePath(kind: 'file' | 'directory'): Promise<void> {
    const api = window.electronAPI;
    const path = kind === 'directory' ? await api?.selectDirectory?.() : await api?.selectFile();
    if (!path) return;
    this.dataSourceForm.patchValue({ url: path });
    const source = this.editedSource();
    if (source && confirm(`Re-import "${source.name}" from ${path}? Its current data will be replaced.`)) {
      this.syncSource(source, undefined, path, this.importerSettings());
    }
  }

  /**
   * The form's Import button. Editing an external source with a path in the field: re-import the
   * file at that path, replacing the source's data. Otherwise: pick a file (uploaded on creation,
   * re-imported right away when editing).
   */
  onImportFile(): void {
    if (this.missingImporter) return;
    const source = this.editedSource();
    const path = (this.dataSourceForm.get('url')?.value || '').trim();
    if (source && path) {
      if (confirm(`Re-import "${source.name}" from ${path}? Its current data will be replaced.`)) {
        this.syncSource(source, undefined, path, this.importerSettings());
      }
      return;
    }
    if (this.isPlugin()) {
      // A plugin reads a path: choose it (desktop app), or type it
      if (this.canUseElectronDialogs) {
        this.choosePath(this.acceptsDirectory() ? 'directory' : 'file');
      } else {
        this.showNotification(`Type the path of the ${this.acceptsDirectory() ? 'directory' : 'file'} to import.`, 'info');
      }
      return;
    }
    this.rdfFileInput.nativeElement.value = ''; // reset so re-selecting the same file still fires 'change'
    this.rdfFileInput.nativeElement.click();
  }

  /** The external source open in the edit form, if any. */
  editedSource(): DataSource | null {
    if (!this.isEditing || !this.currentEditId || !this.isExternal()) return null;
    return this.dataSources.find(d => d.id === this.currentEditId) ?? null;
  }

  onRdfFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (!file) return;

    this.selectedFile = file;
    this.isFileLoading = true;

    // A browser never exposes an absolute path; Electron does, through the preload bridge.
    // Leave whatever the user typed in place when no path comes back.
    const path = this.pathOf(file);
    if (path) {
      this.dataSourceForm.patchValue({ url: path });
    }
    console.log('Selected file:', file.name, 'path:', path ?? '(unavailable)');

    // Editing: the picked file replaces the source's data now (Save only updates the metadata).
    const source = this.editedSource();
    if (source) {
      this.isFileLoading = false;
      if (confirm(`Re-import "${source.name}" from ${file.name}? Its current data will be replaced.`)) {
        this.syncSource(source, file, path, this.importerSettings());
      }
    }
  }

  /** Absolute path of a picked file, when running inside Electron. */
  private pathOf(file: File): string | undefined {
    return window.electronAPI?.getPathForFile?.(file) || undefined;
  }

  onConfigFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files?.[0]) return;
    input.value = '';
    this.showNotification('Configuration import is not available yet.', 'info');
  }
 
  ngOnInit(): void {
    // this.setupConditionalValidation();
    
    this.loadDataSources();
    this.loadImporters();

    this.dataSourceService.dataSources$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sources => {
        this.dataSources = sources;
      });
  }
 
  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
 
  /**
   * ✅ Configure la validation conditionnelle pour tool et url
   */
  // private setupConditionalValidation(): void {
  //   this.dataSourceForm.get('sourceType')?.valueChanges
  //     .pipe(takeUntil(this.destroy$))
  //     .subscribe(type => {
  //       const urlControl = this.dataSourceForm.get('url');
 
  //       if (type === 'EXTERNAL') {
  //         // Source externe: tool et url obligatoires
  //         toolControl?.setValidators([Validators.required]);
  //         urlControl?.setValidators([Validators.required, Validators.minLength(5)]);
          
  //         console.log('✅ Validation EXTERNAL activée: tool et url requis');
  //       } else {
  //         // Source interne: pas de tool ni url
  //         toolControl?.clearValidators();
  //         urlControl?.clearValidators();
          
  //         // Vide les champs
  //         toolControl?.setValue('');
  //         urlControl?.setValue('');
          
  //         console.log('✅ Validation INTERNAL: tool et url non requis');
  //       }
 
  //       // Applique les changements
  //       toolControl?.updateValueAndValidity();
  //       urlControl?.updateValueAndValidity();
  //     });
  // }
 
  loadDataSources(): void {
    this.dataSourceService.getAllDataSources()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (sources) => {
          this.dataSources = sources;
        },
        error: (error) => {
          this.showNotification('Erreur lors du chargement des sources', 'error');
          console.error('Error:', error);
        }
      });
  }
 
  openCreateForm(): void {
    this.showForm = true;
    this.isEditing = false;
    this.currentEditId = null;
    this.selectedDataSource = null;
    this.resetImporterState();
 
    // Détermine le type par défaut selon la contrainte
    if (!this.canCreateInternalSource()) {
      this.dataSourceForm.reset({ sourceType: 'EXTERNAL', importer: RDF_FILE_IMPORTER });
      console.log('⚠️ Source INTERNAL existe → Formulaire en mode EXTERNAL');
    } else {
      this.dataSourceForm.reset({ sourceType: 'INTERNAL', importer: RDF_FILE_IMPORTER });
      console.log('✅ Aucune source INTERNAL → Formulaire en mode INTERNAL');
    }
  }
 
  openEditForm(dataSource: DataSource): void {
    // The *content* of an external source stays read-only, but its metadata — name,
    // description, and above all the file path used on re-import — must be editable.
    if (dataSource.sourceType === 'EXTERNAL') {
      this.showNotification(
        'External data is read-only: you can edit the name, description and file path only.',
        'info'
      );
    }

    this.showForm = true;
    this.isEditing = true;
    this.currentEditId = dataSource.id || null;
    this.resetImporterState();

    // A stored importer without plugin: shown as is, drop-down disabled until a plugin is found
    // or the value is turned to raw RDF.
    this.missingImporter = dataSource.importerStatus === 'MISSING' ? dataSource.importer ?? null : null;
    this.dataSourceForm.patchValue({
      shortName: dataSource.shortName,
      name: dataSource.name,
      description: dataSource.description,
      sourceType: dataSource.sourceType,
      url: dataSource.url || '',
      importer: dataSource.importer || RDF_FILE_IMPORTER,
      importBaseIri: dataSource.importBaseIri || ''
    });
    const importerControl = this.dataSourceForm.get('importer')!;
    if (this.missingImporter) importerControl.disable(); else importerControl.enable();
  }

  private resetImporterState(): void {
    this.missingImporter = null;
    this.lastResult = null;
    this.showResultWarnings = false;
    this.selectedFile = null;
    this.dataSourceForm.get('importer')?.enable();
  }
 
  cancelForm(): void {
    this.showForm = false;
    this.isEditing = false;
    this.currentEditId = null;
    this.resetImporterState();
    this.dataSourceForm.reset({ sourceType: 'INTERNAL', importer: RDF_FILE_IMPORTER });
  }
 
  onSubmit(): void {
    // Marque tous les champs comme "touchés" pour afficher les erreurs
    this.dataSourceForm.markAllAsTouched();
 
    if (this.dataSourceForm.valid) {
      const formValue = this.dataSourceForm.value;
      
      // Vérification contrainte INTERNAL
      if (formValue.sourceType === 'INTERNAL' && !this.canCreateInternalSource() && !this.isEditing) {
        this.showNotification('An internal data source already exists.', 'error');
        return;
      }
      
      // No path check here: an external source can be created from the uploaded bytes
      // alone. Without a path it simply is not syncable in one click (see reimportSource).

      if (this.isEditing && this.currentEditId) {
        this.updateDataSource(this.currentEditId, formValue,
          formValue.sourceType === 'EXTERNAL' ? this.importerSettings() : undefined);
      } else {
        this.createDataSource(formValue);
      }
    } else {
      // Message d'erreur détaillé
      const errors: string[] = [];
      
      if (this.dataSourceForm.get('shortName')?.invalid) {
        errors.push('Short name');
      }
      if (this.dataSourceForm.get('name')?.invalid) {
        errors.push('Display name');
      }
      // if (this.dataSourceForm.get('url')?.invalid) {
      //   errors.push('File path');
      // }
      
      // const errorMessage = errors.length > 0 
      //   ? `Missing fields: ${errors.join(', ')}`
      //   : 'Please fill in all required fields';
      
      // this.showNotification(errorMessage, 'error');
    }
  }


  createDataSource(data: any): void {

    if (data.sourceType === 'INTERNAL') {

      const createObservable = this.dataSourceService.createInternalSource(data);

      createObservable
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (created) => {
            this.showNotification(`Source created: ${created.graphIRI}`, 'success');
            this.cancelForm();
          },
          error: (error) => {
            this.showNotification(`Error: ${error.message}`, 'error');
            console.error('Error:', error);
          }
        });
      return;
    }

    // External: imported from the uploaded file, else from the path (a plugin always reads a path)
    const path = (data.url || '').trim();
    const file = this.isPlugin() ? null : this.selectedFile;
    if (!file && !path) {
      this.showNotification(this.isPlugin()
        ? `Give the path of the ${this.acceptsDirectory() ? 'directory' : 'file'} to import.`
        : 'Select the RDF file, or give its path.', 'error');
      return;
    }
    const externalData: CreateExternalDataSourceRequest = {
      dataSource: data,
      file,
      settings: this.importerSettings()
    };

    this.dataSourceService.createExternalSource(externalData)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.showNotification(result
            ? `Source created: ${this.describeImport(result)}`
            : 'Source created', 'success');
          this.cancelForm();
        },
        error: (error) => {
          // The source may exist, empty, when only its first import failed
          this.loadDataSources();
          this.showNotification(`Error: ${error.message}`, 'error');
          console.error('Error:', error);
        }
      });
  }

  /** "1,234 statements imported (3 warnings)". */
  describeImport(result: ImportResult): string {
    const warnings = result.warnings?.length ?? 0;
    return `${result.tripleCount.toLocaleString()} statements imported`
      + (warnings ? ` (${warnings} warning${warnings > 1 ? 's' : ''})` : '');
  }
 
  updateDataSource(shortName: string, data: Partial<DataSource>, settings?: ImporterSettings): void {
    this.dataSourceService.updateDataSource(shortName, data, settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (updated) => {
          this.showNotification('Source updated', 'success');
          this.cancelForm();
        },
        error: (error) => {
          this.showNotification(
            `Error: ${error.message}`, 
            'error'
          );
        }
      });
  }
 
  deleteDataSource(dataSource: DataSource): void {
    const message = `Delete "${dataSource.name}" and its named graph?`;
    
    if (confirm(message)) {
      this.dataSourceService.deleteDataSource(dataSource.shortName)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.showNotification('Source deleted', 'success');
            if (this.selectedDataSource?.id === dataSource.id) {
              this.selectedDataSource = null;
            }
          },
          error: (error) => {
            this.showNotification(
              `Error: ${error.message}`, 
              'error'
            );
          }
        });
    }
  }
 
  reimportSource(dataSource: DataSource): void {
    if (dataSource.importerStatus === 'MISSING') {
      this.showNotification(`No plugin for this data source: "${dataSource.importer}".`, 'error');
      this.openEditForm(dataSource);
      return;
    }
    if (dataSource.url) {
      if (confirm(`Re-import "${dataSource.name}" from ${dataSource.url}?`)) {
        this.syncSource(dataSource);
      }
      return;
    }

    // A plugin reads a path: set it in the form
    if (dataSource.importerStatus === 'PLUGIN') {
      this.showNotification(`No path is recorded for "${dataSource.name}": set it, then Import.`, 'info');
      this.openEditForm(dataSource);
      return;
    }

    // Nothing recorded to read back from: ask for the file and upload its bytes.
    if (confirm(`No file path is recorded for "${dataSource.name}". Select the RDF file to re-import?`)) {
      this.pickFileForReimport(dataSource);
    }
  }

  /**
   * Re-imports a source. From the form, `settings` are the form's importer, options and base IRI
   * (recorded once the import succeeds); from the list, the recorded ones are used.
   */
  private syncSource(dataSource: DataSource, file?: File, sourceLocation?: string, settings?: ImporterSettings): void {
    this.dataSourceService.syncExternalSource(dataSource.shortName, file, sourceLocation, settings)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.showNotification(`Data re-imported: ${this.describeImport(result)}`, 'success');
          if (this.editedSource()?.shortName === dataSource.shortName) {
            this.lastResult = result;
            this.showResultWarnings = false;
          }
        },
        error: (error) => {
          // The recorded path is unusable — offer the picker rather than dead-ending (RDF files only).
          const rdfFile = (settings?.importer ?? dataSource.importer ?? RDF_FILE_IMPORTER) === RDF_FILE_IMPORTER;
          if (!file && rdfFile && this.isMissingFileError(error)) {
            if (confirm(`${error.message}\n\nSelect the RDF file to re-import now?`)) {
              this.pickFileForReimport(dataSource);
              return;
            }
          }
          this.showNotification(`Error: ${error.message}`, 'error');
        }
      });
  }

  /** True for the 400s that mean "the file I was told about is not usable". */
  private isMissingFileError(error: any): boolean {
    const message = String(error?.message ?? '');
    return error?.status === 400 && (
      message.includes('No location recorded') ||
      message.includes('RDF file not found')
    );
  }

  private pickFileForReimport(dataSource: DataSource): void {
    this.pendingReimport = dataSource;
    this.reimportFileInput.nativeElement.value = '';
    this.reimportFileInput.nativeElement.click();
  }

  onReimportFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const dataSource = this.pendingReimport;
    this.pendingReimport = null;

    if (!file || !dataSource) return;

    // Passing the path along (when Electron gives us one) makes the next sync one-click.
    this.syncSource(dataSource, file, this.pathOf(file));
  }
 
  selectDataSource(source: DataSource): void {
    this.selectedDataSource = source;
    this.showForm = false;
  }
 
  closeDetail(): void {
    this.selectedDataSource = null;
  }
 
  toggleHistory(sourceId: string): void {
    this.expandedHistory = this.expandedHistory === sourceId ? null : sourceId;
  }
 
  copyToClipboard(text: string | undefined): void {
    if (!text) return;
    
    navigator.clipboard.writeText(text).then(() => {
      this.showNotification('Copied to clipboard', 'success');
    }).catch(err => {
      console.error('Copy error', err);
      this.showNotification('Copy error', 'error');
    });
  }
 
  isExternal(): boolean {
    return this.dataSourceForm.get('sourceType')?.value === 'EXTERNAL';
  }
 
  private showNotification(message: string, type: 'success' | 'error' | 'warning' | 'info'): void {
    this.snackBar.open(message, 'Fermer', {
      duration: type === 'error' ? 5000 : 3000,
      horizontalPosition: 'end',
      verticalPosition: 'top',
      panelClass: `snackbar-${type}`
    });
  }
 
  canCreateInternalSource(): boolean {
    const internalSources = this.dataSources.filter(s => 
      s.sourceType?.toUpperCase() === 'INTERNAL'
    );
    console.log('Sources internes:', internalSources.length);
    return internalSources.length === 0;
  }

  toggleFilterMenu(): void {
    this.showFilterMenu = !this.showFilterMenu;
    console.log('🔽 Menu filtre:', this.showFilterMenu ? 'ouvert' : 'fermé');
  }

  applyTypeFilter(type: 'ALL' | 'INTERNAL' | 'EXTERNAL'): void {
    this.filterType = type;
    console.log('🔍 Filtre type:', type);
  }

  applyToolFilter(tool: string): void {
    this.filterTool = tool;
    console.log('🔍 Filtre outil:', tool);
  }

  resetFilters(): void {
    this.filterType = 'ALL';
    this.filterTool = 'ALL';
    this.searchTerm = '';
    console.log('🔄 Filtres réinitialisés');
  }

  getFilteredSources(): DataSource[] {
    let filtered = [...this.dataSources];
    
    // Filtre par type
    if (this.filterType !== 'ALL') {
      filtered = filtered.filter(s => s.sourceType === this.filterType);
    }
    
    if (this.filterTool !== 'ALL') {
      filtered = filtered.filter(s => s.tool === this.filterTool);
    }
    
    // Recherche textuelle
    if (this.searchTerm.trim() !== '') {
      const term = this.searchTerm.toLowerCase();
      filtered = filtered.filter(s => 
        s.name.toLowerCase().includes(term) ||
        s.shortName.toLowerCase().includes(term) ||
        (s.description && s.description.toLowerCase().includes(term))
      );
    }
    
    return filtered;
  }

  getAvailableTools(): string[] {
    return [...new Set(this.dataSources
      .filter(s => s.tool)
      .map(s => s.tool as string))];
  }

  // ===== MÉTHODES POUR BOUTON EXPORT =====
  exportAsJSON(): void {
    const data = this.getFilteredSources();
    const json = JSON.stringify(data, null, 2);
    
    const blob = new Blob([json], { type: 'application/json' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `datasources-${new Date().toISOString().split('T')[0]}.json`;
    link.click();
    window.URL.revokeObjectURL(url);
    
    this.showNotification('JSON export successful', 'success');
  }

  exportAsCSV(): void {
    const data = this.getFilteredSources();
    
    // En-têtes CSV
    const headers = ['Short Name', 'Name', 'Type', 'Tool', 'Editable', 'Graph IRI'];
    
    // Lignes CSV
    const rows = data.map(s => [
      s.shortName,
      s.name,
      s.sourceType,
      s.editable ? 'Yes' : 'No',
      s.graphIRI
    ]);
    
    // Construction CSV
    const csv = [
      headers.join(','),
      ...rows.map(row => row.map(cell => `"${cell}"`).join(','))
    ].join('\n');
    
    // Téléchargement
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `datasources-${new Date().toISOString().split('T')[0]}.csv`;
    link.click();
    window.URL.revokeObjectURL(url);
    
    this.showNotification('CSV export successful', 'success');
  }

  exportConfiguration(): void {
    const config = {
      exportDate: new Date().toISOString(),
      totalSources: this.dataSources.length,
      sources: this.dataSources.map(s => ({
        shortName: s.shortName,
        name: s.name,
        description: s.description,
        type: s.sourceType,
        url: s.url,
        graphIRI: s.graphIRI,
        editable: s.editable
      }))
    };
    
    const json = JSON.stringify(config, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `datasources-config-${new Date().toISOString().split('T')[0]}.json`;
    link.click();
    window.URL.revokeObjectURL(url);
    
    this.showNotification('Configuration export successful', 'success');
  }

/**
 * Valide la structure du fichier de configuration
 */
  // private validateConfigStructure(config: any): boolean {
  //   // Vérifie que c'est un objet avec la propriété 'sources'
  //   if (!config || typeof config !== 'object') {
  //     return false;
  //   }

  //   // Accepte 2 formats:
  //   // Format 1: { sources: [...] }
  //   // Format 2: [...] (array direct)
  //   const sources = Array.isArray(config) ? config : config.sources;

  //   if (!Array.isArray(sources)) {
  //     return false;
  //   }

  //   // Vérifie que chaque source a au minimum un shortName et un name
  //   return sources.every((s: any) => 
  //     s && 
  //     typeof s === 'object' && 
  //     s.shortName && 
  //     s.name &&
  //     (s.type || s.sourceType) // Accepte les 2 noms de champ
  //   );
  // }

  /**
   * Demande confirmation avant import
   */
  // private confirmImport(config: any): void {
  //   const sources = Array.isArray(config) ? config : config.sources;
  //   const count = sources.length;

  //   const message = `Do you want to import ${count} source(s)?\n\n` +
  //     `⚠️ Warning:\n` +
  //     `- Sources with the same shortName will be skipped\n` +
  //     `- Only 1 INTERNAL source can exist\n` +
  //     `- EXTERNAL sources will be created but not automatically synchronized`;

  //   if (confirm(message)) {
  //     this.importConfiguration(sources);
  //   }
  // }

  /**
   * Importe les sources depuis la configuration
   */
  // private importConfiguration(sources: any[]): void {
  //   let successCount = 0;
  //   let errorCount = 0;
  //   let completedCount = 0; // ← NOUVEAU: Compte les requêtes terminées
  //   const errors: string[] = [];
  //   const totalSources = sources.length;

  //   // Compte le nombre de sources INTERNAL dans le fichier
  //   const internalSources = sources.filter(s => 
  //     (s.type || s.sourceType)?.toUpperCase() === 'INTERNAL'
  //   );

  //   // Vérifie la contrainte INTERNAL
  //   if (internalSources.length > 1) {
  //     this.showNotification(
  //       `❌ File contains ${internalSources.length} INTERNAL sources. Only 1 is allowed.`,
  //       'error'
  //     );
  //     return;
  //   }

  //   if (internalSources.length === 1 && !this.canCreateInternalSource()) {
  //     this.showNotification(
  //       '❌ An INTERNAL source already exists. Delete it before importing.',
  //       'error'
  //     );
  //     return;
  //   }

  //   // Affiche notification de démarrage
  //   this.showNotification(`🔄 Importing ${totalSources} source(s)...`, 'info');

  //   // Import de chaque source
  //   sources.forEach((source) => {
  //     const sourceType = (source.type || source.sourceType)?.toUpperCase();

  //     const request: CreateDataSourceRequest = {
  //       shortName: source.shortName,
  //       name: source.name,
  //       description: source.description || '',
  //       sourceType: sourceType as 'INTERNAL' | 'EXTERNAL',
  //       url: source.url || undefined,
  //       tool: source.tool || undefined
  //     };

  //     const createObservable = sourceType === 'INTERNAL'
  //       ? this.dataSourceService.createInternalSource(request)
  //       : this.dataSourceService.createExternalSource(request);

  //     createObservable
  //       .pipe(takeUntil(this.destroy$))
  //       .subscribe({
  //         next: (created) => {
  //           successCount++;
  //           completedCount++;
  //           console.log(`✅ Source importée: ${created.name}`);

            
  //           if (completedCount === totalSources) {
  //             this.showImportSummary(successCount, errorCount, errors);
  //           }
  //         },
  //         error: (error) => {
  //           errorCount++;
  //           completedCount++;
  //           const errorMsg = `${source.shortName}: ${error.message || 'Erreur inconnue'}`;
  //           errors.push(errorMsg);
  //           console.error(`❌ Erreur import ${source.shortName}:`, error);

            
  //           if (completedCount === totalSources) {
  //             this.showImportSummary(successCount, errorCount, errors);
  //           }
  //         }
  //       });
  //   });
  // }

  /**
   * Affiche un résumé de l'import
   */
  // private showImportSummary(successCount: number, errorCount: number, errors: string[]): void {
  //   if (errorCount === 0) {
  //     this.showNotification(
  //       `✅ Import successful! ${successCount} source(s) created`,
  //       'success'
  //     );
  //   } else {
  //     const message = `⚠️ Partial Import:\n` +
  //       `✅ ${successCount} succeeded\n` +
  //       `❌ ${errorCount} failed\n\n` +
  //       `Erreurs:\n${errors.join('\n')}`;
      
  //     console.error('Import errors', errors);
  //     this.showNotification(message, 'warning');
  //   }
  // }
}
