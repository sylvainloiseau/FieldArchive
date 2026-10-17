import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject, debounceTime, map, of, switchMap, takeUntil, catchError } from 'rxjs';
import { MatchingSettingsHttpService } from '../../services/matching-settings-http.service';
import { MatchingHttpService } from '../../services/matching-http.service';
import {
  MatchingConfiguration, MatchingConfigurationEnvelope, TypeCandidate, TypeGroup, ValidationIssue, ValidationReport,
  compactIri
} from '../../models/matching.model';
import { MatchingGroupEditorComponent } from './matching-group-editor/matching-group-editor.component';
import { MatchingPreviewPanelComponent } from './matching-preview-panel/matching-preview-panel.component';
import { ConfirmDialogComponent } from '../../shared/ui/confirm-dialog.component';

/**
 * Edits the project's data matching configuration (matching-configuration.json): defaults,
 * type groups and their compared concepts, with live validation, coverage, a JSON view and a
 * preview of the effect before saving.
 */
@Component({
  selector: 'app-matching-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, DragDropModule, MatButtonModule, MatCheckboxModule, MatTooltipModule,
    MatProgressBarModule, MatchingGroupEditorComponent, MatchingPreviewPanelComponent],
  template: `
    <div class="flex flex-col h-full bg-gray-50">
      <div class="px-6 pt-4 pb-2 flex flex-wrap items-center gap-2">
        <button mat-button routerLink="/data-matching">← Data matching</button>
        <h1 class="text-xl font-semibold">Matching settings</h1>
        <span class="text-xs px-2 py-0.5 rounded-full" [ngClass]="origin === 'project' ? 'bg-blue-100 text-blue-700' : 'bg-gray-200 text-gray-700'">
          {{ origin === 'project' ? 'project configuration' : origin === 'import' ? 'imported, not saved' : 'default configuration' }}
        </span>
        <span *ngIf="dirty" class="text-xs text-amber-700">● unsaved changes</span>
        <span class="flex-1"></span>
        <button mat-flat-button color="primary" [disabled]="!config || !dirty || hasErrors || saving" (click)="save()"
                [matTooltip]="hasErrors ? 'Fix the errors first' : ''">Save</button>
        <button mat-stroked-button [disabled]="!dirty" (click)="discard()">Discard</button>
        <button mat-stroked-button (click)="reset()">Reset to default…</button>
        <button mat-stroked-button (click)="fileInput.click()">Import…</button>
        <button mat-stroked-button (click)="export()">Export</button>
        <input #fileInput type="file" accept=".json,application/json" class="hidden" (change)="importFile($event)">
        <div class="ml-2 inline-flex rounded-lg border overflow-hidden text-sm border-gray-200">
          <button class="px-3 py-1" [ngClass]="view === 'form' ? 'bg-blue-600 text-white' : 'bg-white'" (click)="setView('form')">Form</button>
          <button class="px-3 py-1" [ngClass]="view === 'json' ? 'bg-blue-600 text-white' : 'bg-white'" (click)="setView('json')">JSON</button>
        </div>
      </div>
      <mat-progress-bar *ngIf="loading || saving" mode="indeterminate"></mat-progress-bar>
      <div *ngIf="loadError" class="mx-6 p-3 rounded bg-red-50 text-red-700 text-sm">{{ loadError }}</div>

      <ng-container *ngIf="config">
        <!-- JSON view -->
        <div *ngIf="view === 'json'" class="flex-1 flex flex-col px-6 pb-4 overflow-hidden">
          <textarea class="flex-1 w-full font-mono text-xs p-3 border rounded-lg bg-white border-gray-200" spellcheck="false"
                    [(ngModel)]="jsonText"></textarea>
          <div class="flex items-center gap-2 mt-2">
            <button mat-flat-button color="primary" (click)="applyJson()">Apply to form</button>
            <span class="text-xs text-gray-500">CURIEs, the short notebook form (a concept as a list of predicates) and missing ids are accepted.</span>
            <span *ngIf="jsonError" class="text-sm text-red-700">{{ jsonError }}</span>
          </div>
        </div>

        <!-- form view -->
        <div *ngIf="view === 'form'" class="flex flex-1 overflow-hidden gap-4 px-6 pb-2">
          <aside class="w-72 flex flex-col gap-4 overflow-y-auto">
            <div class="bg-white rounded-xl border p-3 space-y-2 text-sm border-gray-200">
              <div class="text-xs uppercase tracking-wide text-gray-500">Global defaults</div>
              <label class="flex items-center justify-between gap-2"
                     matTooltip="A pair is proposed when its score reaches this value (0–1).">
                Threshold
                <input type="number" min="0" max="1" step="0.05" class="w-20 px-1 border rounded border-gray-200" [(ngModel)]="config.defaults.threshold" (ngModelChange)="touch()">
              </label>
              <label class="flex items-center justify-between gap-2"
                     matTooltip="The total weight of the properties that must be comparable before a pair can be proposed. Prevents a match on a single minor property.">
                Min. evidence
                <input type="number" min="0" step="0.5" class="w-20 px-1 border rounded border-gray-200" [(ngModel)]="config.defaults.minimumComparedWeight" (ngModelChange)="touch()">
              </label>
              <label class="flex items-center justify-between gap-2" matTooltip="Each entity keeps only its best proposals.">
                Proposals / entity
                <input type="number" min="1" max="50" step="1" class="w-20 px-1 border rounded border-gray-200" [(ngModel)]="config.defaults.maxProposalsPerEntity" (ngModelChange)="touch()">
              </label>
              <label class="flex items-center justify-between gap-2"
                     matTooltip="Above this number of possible pairs in a group, only entities sharing a blocking key are compared.">
                Blocking above
                <input type="number" min="0" step="10000" class="w-24 px-1 border rounded border-gray-200" [(ngModel)]="config.defaults.blockingMinimumPairs" (ngModelChange)="touch()">
              </label>
              <mat-checkbox [(ngModel)]="config.defaults.compareUntypedEntities" (ngModelChange)="touch()">Compare untyped entities</mat-checkbox>
              <mat-checkbox [(ngModel)]="config.defaults.includeSubclasses" (ngModelChange)="touch()">Include subclasses</mat-checkbox>
              <p *ngFor="let i of issuesAt('defaults')" class="text-xs text-red-700">✕ {{ i.path }}: {{ i.message }}</p>
            </div>

            <div class="bg-white rounded-xl border p-3 text-sm border-gray-200">
              <div class="flex items-center mb-2">
                <div class="text-xs uppercase tracking-wide text-gray-500">Type groups</div>
                <span class="flex-1"></span>
                <button class="text-blue-700 text-sm" (click)="addGroup()">+ New</button>
              </div>
              <div cdkDropList (cdkDropListDropped)="drop($event)" class="space-y-1">
                <div *ngFor="let g of config.equivalentGroups; let i = index" cdkDrag
                     class="flex items-center gap-1 px-2 py-1.5 rounded cursor-pointer border"
                     [ngClass]="i === selectedIndex ? 'bg-blue-50 border-blue-300' : 'bg-white border-transparent hover:bg-gray-50'"
                     (click)="selectedIndex = i">
                  <span cdkDragHandle class="text-gray-400 cursor-move" matTooltip="Drag to reorder: the order breaks ties">⋮⋮</span>
                  <span class="flex-1 truncate">{{ g.groupLabel || '(no label)' }}</span>
                  <span class="text-xs text-gray-400">{{ conceptCount(g) }}</span>
                  <span *ngIf="groupHas(i, 'error')" class="text-red-600 text-xs">✕</span>
                  <span *ngIf="!groupHas(i, 'error') && groupHas(i, 'warning')" class="text-amber-600 text-xs">⚠</span>
                </div>
              </div>
              <p class="text-xs text-gray-500 mt-2">The order breaks ties between equally specific groups.</p>
            </div>
            <div class="text-xs">
              <span [ngClass]="hasErrors ? 'text-red-700' : 'text-gray-500'">{{ report.errors.length }} error(s)</span> ·
              <span class="text-gray-500">{{ report.warnings.length - unusedPredicateCount }} warning(s)</span>
              <span *ngIf="unusedPredicateCount" class="text-gray-400"
                    matTooltip="Predicates of the configuration that the project's data never uses; they are greyed in the lists">
                · {{ unusedPredicateCount }} unused predicate(s)</span>
            </div>
          </aside>

          <section class="flex-1 overflow-y-auto bg-white rounded-xl border p-4 border-gray-200">
            <ng-container *ngIf="selectedGroup as g; else noGroup">
              <div class="flex items-center mb-3">
                <h2 class="text-lg font-semibold flex-1">{{ g.groupLabel || 'New group' }}</h2>
                <button mat-button (click)="duplicateGroup(selectedIndex)">Duplicate</button>
                <button mat-button color="warn" (click)="deleteGroup(selectedIndex)">Delete</button>
              </div>
              <app-matching-group-editor [group]="g" [defaults]="config.defaults" [prefixes]="config.prefixes"
                                         [includeSubclasses]="config.defaults.includeSubclasses"
                                         [typeCandidates]="typeCandidates" [idLocked]="savedIds.has(g.id)"
                                         [groupIssues]="groupIssues(selectedIndex)" (changed)="touch()">
              </app-matching-group-editor>
            </ng-container>
            <ng-template #noGroup><p class="text-sm text-gray-500">Select or create a type group.</p></ng-template>
          </section>
        </div>

        <div class="mx-6 mb-4 p-3 bg-white rounded-xl border border-gray-200">
          <app-matching-preview-panel [configuration]="config" [dataSources]="dataSources"></app-matching-preview-panel>
        </div>
      </ng-container>
    </div>
  `
})
export class MatchingSettingsComponent implements OnInit, OnDestroy {
  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  config: MatchingConfiguration | null = null;
  origin: MatchingConfigurationEnvelope['origin'] = 'default';
  report: ValidationReport = { errors: [], warnings: [] };
  typeCandidates: TypeCandidate[] = [];
  dataSources: string[] = [];
  savedIds = new Set<string>();
  selectedIndex = 0;
  view: 'form' | 'json' = 'form';
  jsonText = '';
  jsonError: string | null = null;
  loading = false;
  saving = false;
  loadError: string | null = null;
  private snapshot = '';
  private validate$ = new Subject<void>();
  private destroy$ = new Subject<void>();

  constructor(private settings: MatchingSettingsHttpService,
              private matching: MatchingHttpService,
              private dialog: MatDialog,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.validate$.pipe(
      debounceTime(500),
      switchMap(() => this.config ? this.settings.validate(this.config).pipe(catchError(() => of(null))) : of(null)),
      takeUntil(this.destroy$)
    ).subscribe(r => { if (r) this.report = r; });
    this.load();
    this.settings.types().subscribe({ next: t => this.typeCandidates = t, error: () => this.typeCandidates = [] });
    this.matching.overview().subscribe({
      next: o => this.dataSources = o.dataSources.map(d => d.shortName),
      error: () => this.dataSources = []
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get dirty(): boolean {
    return !!this.config && JSON.stringify(this.config) !== this.snapshot;
  }

  get unusedPredicateCount(): number {
    return this.report.warnings.filter(w => w.code === 'UNUSED_PREDICATE').length;
  }

  get hasErrors(): boolean {
    return this.report.errors.length > 0;
  }

  get selectedGroup(): TypeGroup | null {
    return this.config?.equivalentGroups[this.selectedIndex] ?? null;
  }

  /** Used by the route guard. */
  canLeave(): Observable<boolean> {
    if (!this.dirty) return of(true);
    return this.confirm('Leave the settings?', 'Your changes are not saved and will be lost.', 'Leave');
  }

  private load(): void {
    this.loading = true;
    this.settings.get().subscribe({
      next: env => {
        this.setEnvelope(env, true);
        this.loading = false;
      },
      error: err => {
        this.loadError = MatchingHttpService.errorMessage(err);
        this.loading = false;
      }
    });
  }

  private setEnvelope(env: MatchingConfigurationEnvelope, asSaved: boolean): void {
    this.config = env.configuration;
    this.origin = env.origin;
    this.report = env.validation;
    if (asSaved) {
      this.snapshot = JSON.stringify(env.configuration);
      this.savedIds = new Set(env.origin === 'project' ? env.configuration.equivalentGroups.map(g => g.id) : []);
    }
    this.selectedIndex = Math.min(this.selectedIndex, Math.max(0, env.configuration.equivalentGroups.length - 1));
    this.jsonText = this.toJson(env.configuration);
  }

  touch(): void {
    this.validate$.next();
  }

  // =========================
  //  Groups
  // =========================

  conceptCount(g: TypeGroup): number {
    return Object.keys(g.properties ?? {}).length;
  }

  addGroup(): void {
    if (!this.config) return;
    let n = 1;
    const ids = new Set(this.config.equivalentGroups.map(g => g.id));
    while (ids.has('group-' + n)) n++;
    this.config.equivalentGroups.push({
      id: 'group-' + n, groupLabel: 'New group', note: null, equivalentTypes: [], threshold: null,
      minimumComparedWeight: null, requiredConcepts: ['name'],
      properties: { name: { predicates: [], comparator: 'name', weight: 3, blocking: true, options: {} } }
    });
    this.selectedIndex = this.config.equivalentGroups.length - 1;
    this.touch();
  }

  duplicateGroup(i: number): void {
    if (!this.config) return;
    const copy: TypeGroup = JSON.parse(JSON.stringify(this.config.equivalentGroups[i]));
    const ids = new Set(this.config.equivalentGroups.map(g => g.id));
    let id = copy.id + '-copy';
    for (let n = 2; ids.has(id); n++) id = `${copy.id}-copy-${n}`;
    copy.id = id;
    copy.groupLabel = copy.groupLabel + ' (copy)';
    this.config.equivalentGroups.splice(i + 1, 0, copy);
    this.selectedIndex = i + 1;
    this.touch();
  }

  deleteGroup(i: number): void {
    const g = this.config?.equivalentGroups[i];
    if (!g) return;
    const referenced = this.report.warnings.some(w => w.code === 'REFERENCED_GROUP_REMOVED') || this.savedIds.has(g.id);
    this.confirm(`Delete the group "${g.groupLabel}"?`,
      referenced ? 'Stored links may refer to this group; they keep their decisions but lose their group.' : 'The group is removed when you save.',
      'Delete').subscribe(ok => {
      if (!ok || !this.config) return;
      this.config.equivalentGroups.splice(i, 1);
      this.selectedIndex = Math.max(0, i - 1);
      this.touch();
    });
  }

  drop(event: CdkDragDrop<TypeGroup[]>): void {
    if (!this.config) return;
    moveItemInArray(this.config.equivalentGroups, event.previousIndex, event.currentIndex);
    this.selectedIndex = event.currentIndex;
    this.touch();
  }

  // =========================
  //  Validation display
  // =========================

  issuesAt(prefix: string): ValidationIssue[] {
    return this.report.errors.filter(i => i.path.startsWith(prefix));
  }

  groupHas(i: number, severity: 'error' | 'warning'): boolean {
    const list = severity === 'error' ? this.report.errors : this.report.warnings.filter(w => w.code !== 'UNUSED_PREDICATE');
    return list.some(x => x.path.startsWith(`equivalentGroups[${i}]`));
  }

  groupIssues(i: number) {
    const prefix = `equivalentGroups[${i}]`;
    const rel = (p: string) => p.substring(prefix.length).replace(/^\./, '') || 'group';
    return [
      ...this.report.errors.filter(x => x.path.startsWith(prefix)).map(issue => ({ severity: 'error' as const, issue, relativePath: rel(issue.path) })),
      ...this.report.warnings.filter(x => x.path.startsWith(prefix)).map(issue => ({ severity: 'warning' as const, issue, relativePath: rel(issue.path) }))
    ];
  }

  // =========================
  //  Save, reset, import, export, JSON
  // =========================

  save(): void {
    if (!this.config) return;
    this.saving = true;
    const before = this.snapshot;
    this.settings.save(this.config).subscribe({
      next: env => {
        this.saving = false;
        const changed = before !== JSON.stringify(env.configuration);
        this.setEnvelope(env, true);
        this.snackBar.open(changed ? 'Saved. Proposals computed with the previous settings are now out of date: rerun them in Data matching.'
          : 'Saved.', 'Close', { duration: 6000 });
      },
      error: err => {
        this.saving = false;
        if (err?.error?.validation) this.report = err.error.validation;
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
      }
    });
  }

  discard(): void {
    if (!this.config) return;
    this.config = JSON.parse(this.snapshot);
    this.jsonText = this.toJson(this.config!);
    this.touch();
  }

  reset(): void {
    this.confirm('Reset to the default configuration?',
      "The project's matching settings are deleted and the bundled default applies again. Your link decisions are kept.",
      'Reset').subscribe(ok => {
      if (!ok) return;
      this.settings.reset().subscribe({
        next: env => {
          this.setEnvelope(env, true);
          this.snackBar.open('The default configuration applies.', 'Close', { duration: 4000 });
        },
        error: err => this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 })
      });
    });
  }

  export(): void {
    this.settings.exportFile().subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'matching-configuration.json';
        a.click();
        URL.revokeObjectURL(url);
      },
      error: err => this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 })
    });
  }

  importFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.loadImported(file);
  }

  setView(view: 'form' | 'json'): void {
    if (view === 'json' && this.config) this.jsonText = this.toJson(this.config);
    this.jsonError = null;
    this.view = view;
  }

  /** The JSON text goes through the backend parser, which accepts every form the file may have. */
  applyJson(): void {
    try {
      JSON.parse(this.jsonText);
    } catch (e) {
      this.jsonError = 'Not valid JSON: ' + (e as Error).message;
      return;
    }
    this.loadImported(new File([this.jsonText], 'configuration.json', { type: 'application/json' }), true);
  }

  private loadImported(file: File, fromJsonView = false): void {
    this.settings.importFile(file).subscribe({
      next: env => {
        const origin = this.origin;
        this.setEnvelope(env, false);
        if (fromJsonView) this.origin = origin;
        this.jsonError = null;
        if (fromJsonView) this.view = 'form';
        this.snackBar.open(env.validation.errors.length
          ? `Loaded with ${env.validation.errors.length} error(s) to fix before saving.`
          : 'Loaded into the editor; save to apply it.', 'Close', { duration: 5000 });
      },
      error: err => {
        this.jsonError = MatchingHttpService.errorMessage(err);
        this.snackBar.open(this.jsonError, 'Close', { duration: 8000 });
      }
    });
  }

  /** The configuration with CURIEs, as it is written in the project file. */
  private toJson(c: MatchingConfiguration): string {
    const copy: MatchingConfiguration = JSON.parse(JSON.stringify(c));
    for (const g of copy.equivalentGroups) {
      g.equivalentTypes = g.equivalentTypes.map(t => compactIri(t, copy.prefixes));
      for (const concept of Object.values(g.properties)) {
        concept.predicates = concept.predicates.map(p => compactIri(p, copy.prefixes));
      }
    }
    return JSON.stringify(copy, null, 2);
  }

  private confirm(title: string, message: string, confirmLabel: string): Observable<boolean> {
    return this.dialog.open(ConfirmDialogComponent, { width: '460px', data: { title, message, confirmLabel } })
      .afterClosed().pipe(map(ok => !!ok));
  }
}
