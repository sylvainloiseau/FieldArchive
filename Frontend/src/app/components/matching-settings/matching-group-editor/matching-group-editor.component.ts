import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, debounceTime, switchMap, takeUntil, catchError, of } from 'rxjs';
import { MatchingSettingsHttpService } from '../../../services/matching-settings-http.service';
import {
  COMPARATOR_HELP, ComparatorName, GroupCoverage, MatchingConcept, MatchingDefaults, PredicateCandidate,
  TypeCandidate, TypeGroup, ValidationIssue, compactIri
} from '../../../models/matching.model';
import { IriChipInputComponent, IriOption } from '../iri-chip-input/iri-chip-input.component';

const COMPARATORS: ComparatorName[] = ['auto', 'string', 'name', 'text', 'exact', 'date', 'number', 'linkedEntity'];

/** The options each comparator accepts (mirrors the backend ComparatorKind). */
const COMPARATOR_OPTIONS: Record<ComparatorName, { name: string; label: string; kind: 'unit' | 'nonNegative' | 'boolean'; default: number | boolean }[]> = {
  auto: [], string: [], name: [], text: [],
  exact: [{ name: 'caseSensitive', label: 'Case sensitive', kind: 'boolean', default: false }],
  date: [{ name: 'partialOverlapScore', label: 'Score of overlapping dates', kind: 'unit', default: 0.85 }],
  number: [{ name: 'tolerance', label: 'Tolerance', kind: 'nonNegative', default: 0 }],
  linkedEntity: [{ name: 'labelFactor', label: 'Weight of a similar label vs the same entity', kind: 'unit', default: 0.9 }]
};

/** Edits one type group in place: its types, overrides and compared concepts. */
@Component({
  selector: 'app-matching-group-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTooltipModule, IriChipInputComponent],
  template: `
    <div class="space-y-4">
      <div class="grid grid-cols-[1fr_12rem] gap-3">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Label</mat-label>
          <input matInput [(ngModel)]="group.groupLabel" (ngModelChange)="touch()">
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic"
                        [matTooltip]="idLocked ? 'The id is fixed once saved: stored links refer to it.' : 'Lowercase letters, digits and dashes.'">
          <mat-label>Id</mat-label>
          <input matInput [(ngModel)]="group.id" (ngModelChange)="touch()" [readonly]="idLocked">
        </mat-form-field>
      </div>
      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-full">
        <mat-label>Note</mat-label>
        <input matInput [(ngModel)]="group.note" (ngModelChange)="touch()">
      </mat-form-field>

      <div>
        <div class="text-sm font-medium mb-1">Equivalent types</div>
        <app-iri-chip-input [values]="group.equivalentTypes" [options]="typeOptions" [prefixes]="prefixes"
                            placeholder="Add a type…" (valuesChange)="group.equivalentTypes = $event; touch(true)"></app-iri-chip-input>
        <p class="text-xs text-gray-500 mt-1" *ngIf="coverage">
          {{ includeSubclasses ? 'With their subclasses: ' : '' }}
          <span *ngFor="let s of coverage.sources; let last = last">{{ s.dataSource }} {{ s.entityCount }}{{ last ? '' : ' · ' }}</span>
          entities
        </p>
      </div>

      <div class="flex gap-3">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-48"
                        matTooltip="A pair is proposed when its score reaches this value (0–1).">
          <mat-label>Threshold</mat-label>
          <input matInput type="number" min="0" max="1" step="0.05" [placeholder]="'default ' + defaults.threshold"
                 [ngModel]="group.threshold" (ngModelChange)="group.threshold = num($event); touch()">
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-48"
                        matTooltip="Total weight of the concepts that must be comparable before a pair can be proposed.">
          <mat-label>Min. evidence</mat-label>
          <input matInput type="number" min="0" step="0.5" [placeholder]="'default ' + defaults.minimumComparedWeight"
                 [ngModel]="group.minimumComparedWeight" (ngModelChange)="group.minimumComparedWeight = num($event); touch()">
        </mat-form-field>
      </div>

      <div>
        <div class="flex items-center mb-1">
          <div class="text-sm font-medium">Compared concepts</div>
          <span class="flex-1"></span>
          <button mat-stroked-button (click)="addConcept()">+ Add concept</button>
        </div>
        <table class="w-full text-sm">
          <thead>
            <tr class="text-xs text-gray-500 text-left">
              <th class="py-1 w-36">Concept</th>
              <th class="py-1">Predicates</th>
              <th class="py-1 w-36">Compare</th>
              <th class="py-1 w-36" matTooltip="Weight of the concept in the mean">Weight</th>
              <th class="py-1 w-10 text-center" matTooltip="Required: a pair is only proposed when this concept could be compared">Req</th>
              <th class="py-1 w-10 text-center" matTooltip="Blocking key: on large sources, only entities sharing a key are compared">Blk</th>
              <th class="py-1 w-32" matTooltip="Share of the group's entities that have a value, per source">Coverage</th>
              <th class="w-14"></th>
            </tr>
          </thead>
          <tbody>
            <ng-container *ngFor="let key of conceptKeys(); trackBy: trackKey">
              <tr class="border-t border-gray-100 align-top" *ngIf="group.properties[key] as c">
                <td class="py-1 pr-2">
                  <input class="w-full px-1 py-0.5 border rounded" [ngModel]="key" (change)="rename(key, $any($event.target).value)">
                </td>
                <td class="py-1 pr-2">
                  <app-iri-chip-input [values]="c.predicates" [options]="predicateOptions" [prefixes]="prefixes"
                                      placeholder="Add a predicate…" (valuesChange)="c.predicates = $event; touch(true)"></app-iri-chip-input>
                  <p *ngIf="comparatorWarning(c) as w" class="text-xs text-amber-700 mt-0.5">{{ w }}</p>
                </td>
                <td class="py-1 pr-2">
                  <select class="w-full px-1 py-1 border rounded" [(ngModel)]="c.comparator" (ngModelChange)="onComparator(c)"
                          [title]="help(c.comparator)">
                    <option *ngFor="let k of comparators" [value]="k">{{ k }}</option>
                  </select>
                  <p class="text-[11px] text-gray-500 leading-tight mt-0.5">{{ help(c.comparator) }}</p>
                </td>
                <td class="py-1 pr-2">
                  <div class="flex items-center gap-1">
                    <input type="range" min="0.5" max="10" step="0.5" class="w-20" [(ngModel)]="c.weight" (ngModelChange)="touch()">
                    <input type="number" min="0.5" max="100" step="0.5" class="w-14 px-1 border rounded" [(ngModel)]="c.weight" (ngModelChange)="touch()">
                  </div>
                </td>
                <td class="py-1 text-center">
                  <input type="checkbox" [checked]="group.requiredConcepts.includes(key)" (change)="toggleRequired(key)">
                </td>
                <td class="py-1 text-center">
                  <input type="checkbox" [(ngModel)]="c.blocking" (ngModelChange)="touch()">
                </td>
                <td class="py-1 text-xs">
                  <div *ngFor="let s of coverage?.sources ?? []" [hidden]="!s.entityCount"
                       [ngClass]="coverageClass(key, s.concepts[key]?.percent ?? 0)">
                    {{ s.dataSource }} {{ s.concepts[key]?.percent ?? 0 | number:'1.0-0' }} %
                  </div>
                </td>
                <td class="py-1 text-right whitespace-nowrap">
                  <button *ngIf="optionsOf(c).length" class="px-1 text-gray-600" matTooltip="Options" (click)="toggleOptions(key)">⚙</button>
                  <button class="px-1 text-red-600" matTooltip="Remove" (click)="removeConcept(key)">×</button>
                </td>
              </tr>
              <tr *ngIf="openOptions === key && group.properties[key] as c">
                <td></td>
                <td colspan="7" class="pb-2">
                  <div class="flex flex-wrap gap-4 p-2 rounded bg-gray-50 text-xs">
                    <label *ngFor="let o of optionsOf(c)" class="flex items-center gap-1">
                      {{ o.label }}
                      <input *ngIf="o.kind === 'boolean'" type="checkbox" [checked]="optionValue(c, o) === true"
                             (change)="setOption(c, o.name, $any($event.target).checked)">
                      <input *ngIf="o.kind !== 'boolean'" type="number" class="w-20 px-1 border rounded"
                             [min]="0" [max]="o.kind === 'unit' ? 1 : null" step="0.05"
                             [ngModel]="optionValue(c, o)" (ngModelChange)="setOption(c, o.name, num($event))">
                    </label>
                  </div>
                </td>
              </tr>
            </ng-container>
          </tbody>
        </table>
      </div>

      <div *ngIf="suggestions.length">
        <button type="button" class="text-sm text-blue-700" (click)="showSuggestions = !showSuggestions">
          {{ showSuggestions ? '▾' : '▸' }} Predicates used by these types and not compared ({{ suggestions.length }})
        </button>
        <div *ngIf="showSuggestions" class="mt-1 border rounded-lg divide-y">
          <div *ngFor="let p of suggestions" class="flex items-center gap-2 px-2 py-1 text-sm">
            <div class="flex-1 min-w-0">
              <span class="font-medium">{{ p.curie }}</span>
              <span class="text-xs text-gray-500 ml-1">{{ p.kind }} · {{ usageText(p) }}</span>
              <div class="text-xs text-gray-400 truncate">{{ p.sampleValues.join(' · ') }}</div>
            </div>
            <span class="text-xs text-gray-500">{{ p.suggestedComparator }}</span>
            <button mat-button (click)="addSuggestion(p)">Add as concept</button>
            <select class="text-xs border rounded px-1 py-0.5" (change)="addToConcept(p, $any($event.target).value); $any($event.target).value = ''">
              <option value="">Add to…</option>
              <option *ngFor="let k of conceptKeys()" [value]="k">{{ k }}</option>
            </select>
          </div>
        </div>
      </div>

      <div *ngIf="issues.length" class="text-xs space-y-0.5">
        <p *ngFor="let i of issues" [ngClass]="i.severity === 'error' ? 'text-red-700' : 'text-amber-700'">
          {{ i.severity === 'error' ? '✕' : '⚠' }} <span class="text-gray-500">{{ i.where }}</span> {{ i.message }}
        </p>
      </div>
    </div>
  `
})
export class MatchingGroupEditorComponent implements OnInit, OnChanges, OnDestroy {
  @Input({ required: true }) group!: TypeGroup;
  @Input({ required: true }) defaults!: MatchingDefaults;
  @Input() prefixes: Record<string, string> = {};
  @Input() includeSubclasses = true;
  @Input() typeCandidates: TypeCandidate[] = [];
  @Input() idLocked = false;
  /** Issues of this group, with paths relative to the group ("properties.name.weight"). */
  @Input() groupIssues: { severity: 'error' | 'warning'; issue: ValidationIssue; relativePath: string }[] = [];
  @Output() changed = new EventEmitter<void>();

  readonly comparators = COMPARATORS;
  predicateCandidates: PredicateCandidate[] = [];
  coverage: GroupCoverage | null = null;
  showSuggestions = false;
  openOptions: string | null = null;
  private refresh$ = new Subject<void>();
  private destroy$ = new Subject<void>();

  constructor(private settings: MatchingSettingsHttpService) {}

  ngOnInit(): void {
    this.refresh$.pipe(
      debounceTime(400),
      switchMap(() => this.settings.predicates(this.group.equivalentTypes).pipe(catchError(() => of([] as PredicateCandidate[])))),
      takeUntil(this.destroy$)
    ).subscribe(p => this.predicateCandidates = p);
    this.refresh$.pipe(
      debounceTime(600),
      switchMap(() => this.settings.coverage(this.group, this.prefixes, this.includeSubclasses).pipe(catchError(() => of(null)))),
      takeUntil(this.destroy$)
    ).subscribe(c => this.coverage = c);
    this.refresh$.next();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['group'] && !changes['group'].firstChange) {
      this.openOptions = null;
      this.refresh$.next();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get typeOptions(): IriOption[] {
    return this.typeCandidates.map(t => ({
      iri: t.iri, curie: t.curie, label: t.label,
      hint: Object.keys(t.usage).length ? this.usage(t.usage) : (t.defined ? 'defined' : '')
    }));
  }

  get predicateOptions(): IriOption[] {
    return this.predicateCandidates.map(p => ({
      iri: p.iri, curie: p.curie, label: p.label,
      hint: Object.keys(p.usage).length ? this.usage(p.usage) : 'unused',
      unused: !Object.keys(p.usage).length
    }));
  }

  get suggestions(): PredicateCandidate[] {
    const used = new Set(Object.values(this.group.properties).flatMap(c => c?.predicates ?? []));
    return this.predicateCandidates.filter(p => Object.keys(p.usage).length && !used.has(p.iri));
  }

  get issues(): { severity: 'error' | 'warning'; where: string; message: string }[] {
    // unused predicates are shown greyed in the chips, not repeated here
    return this.groupIssues
      .filter(i => i.issue.code !== 'UNUSED_PREDICATE')
      .map(i => ({ severity: i.severity, where: i.relativePath, message: i.issue.message }));
  }

  conceptKeys(): string[] {
    return Object.keys(this.group.properties);
  }

  trackKey(_: number, key: string): string {
    return key;
  }

  touch(refresh = false): void {
    this.changed.emit();
    if (refresh) this.refresh$.next();
  }

  num(v: unknown): number | null {
    if (v === '' || v === null || v === undefined) return null;
    const n = Number(v);
    return isNaN(n) ? null : n;
  }

  help(c: ComparatorName): string {
    return COMPARATOR_HELP[c] ?? '';
  }

  usageText(p: PredicateCandidate): string {
    return this.usage(p.usage);
  }

  private usage(u: Record<string, number>): string {
    return Object.entries(u).map(([s, n]) => `${s} ${n}`).join(', ');
  }

  coverageClass(key: string, percent: number): string {
    if (this.group.requiredConcepts.includes(key) && percent < 50) return 'text-red-600 font-medium';
    return percent === 0 ? 'text-gray-400' : 'text-gray-700';
  }

  comparatorWarning(c: MatchingConcept): string | null {
    const kinds = c.predicates
      .map(p => this.predicateCandidates.find(x => x.iri === p)?.kind)
      .filter((k): k is string => !!k && k !== 'unknown');
    if (!kinds.length) return null;
    const allIri = kinds.every(k => k === 'iri');
    if (c.comparator === 'number' && allIri) return 'These predicates link to entities: numbers cannot be compared.';
    if (c.comparator === 'date' && allIri) return 'Linked entities are only compared as dates when they are rico:Date entities.';
    if (['string', 'name', 'exact', 'text'].includes(c.comparator) && allIri) return 'Linked entities will be compared by their name.';
    if (c.comparator === 'linkedEntity' && kinds.every(k => k.startsWith('literal'))) return 'These predicates hold literals: they are compared as names.';
    return null;
  }

  addConcept(): void {
    let i = 1;
    while (this.group.properties['concept' + i]) i++;
    this.group.properties['concept' + i] = { predicates: [], comparator: 'auto', weight: 1, blocking: false, options: {} };
    this.touch();
  }

  removeConcept(key: string): void {
    delete this.group.properties[key];
    this.group.requiredConcepts = this.group.requiredConcepts.filter(k => k !== key);
    this.touch(true);
  }

  rename(oldKey: string, newKey: string): void {
    newKey = (newKey || '').trim();
    if (!newKey || newKey === oldKey || this.group.properties[newKey]) return;
    const rebuilt: Record<string, MatchingConcept> = {};
    for (const [k, v] of Object.entries(this.group.properties)) rebuilt[k === oldKey ? newKey : k] = v;
    this.group.properties = rebuilt;
    this.group.requiredConcepts = this.group.requiredConcepts.map(k => k === oldKey ? newKey : k);
    this.touch(true);
  }

  toggleRequired(key: string): void {
    const r = this.group.requiredConcepts;
    this.group.requiredConcepts = r.includes(key) ? r.filter(k => k !== key) : [...r, key];
    this.touch();
  }

  onComparator(c: MatchingConcept): void {
    const allowed = new Set(COMPARATOR_OPTIONS[c.comparator].map(o => o.name));
    for (const k of Object.keys(c.options ?? {})) if (!allowed.has(k)) delete c.options[k];
    this.touch();
  }

  optionsOf(c: MatchingConcept) {
    return COMPARATOR_OPTIONS[c.comparator] ?? [];
  }

  optionValue(c: MatchingConcept, o: { name: string; default: number | boolean }): number | boolean {
    return c.options?.[o.name] ?? o.default;
  }

  setOption(c: MatchingConcept, name: string, value: number | boolean | null): void {
    c.options = c.options ?? {};
    if (value === null) delete c.options[name]; else c.options[name] = value;
    this.touch();
  }

  toggleOptions(key: string): void {
    this.openOptions = this.openOptions === key ? null : key;
  }

  addSuggestion(p: PredicateCandidate): void {
    let key = compactIri(p.iri, this.prefixes).replace(/^.*:/, '').replace(/[^A-Za-z0-9_]/g, '_');
    if (!/^[A-Za-z]/.test(key)) key = 'p_' + key;
    let unique = key;
    for (let i = 2; this.group.properties[unique]; i++) unique = key + i;
    this.group.properties[unique] = {
      predicates: [p.iri], comparator: p.suggestedComparator, weight: 1, blocking: false, options: {}
    };
    this.touch(true);
  }

  addToConcept(p: PredicateCandidate, key: string): void {
    const c = this.group.properties[key];
    if (!c || c.predicates.includes(p.iri)) return;
    c.predicates = [...c.predicates, p.iri];
    this.touch(true);
  }
}
