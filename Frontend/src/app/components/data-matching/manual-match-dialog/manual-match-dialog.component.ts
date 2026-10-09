import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Subject, debounceTime, switchMap, takeUntil, catchError, of } from 'rxjs';
import { MatchingHttpService } from '../../../services/matching-http.service';
import { MatchingActionsService } from '../../../services/matching-actions.service';
import { EntityNode, MatchableEntity, PairComparison, nodeName } from '../../../models/matching.model';
import { MatchingEvidenceComponent } from '../matching-evidence/matching-evidence.component';

export interface ManualMatchDialogData {
  dataSourceA: string;
  dataSourceB: string;
  typeGroups: { id: string; label: string }[];
  /** Pre-selects one side (from a row: "none of these, link manually"). */
  prefillA?: EntityNode | null;
  prefillB?: EntityNode | null;
}

interface SideState {
  dataSource: string;
  other: string;
  query: string;
  results: MatchableEntity[];
  selected: EntityNode | null;
  loading: boolean;
  search$: Subject<void>;
}

/** Links two entities by hand: one search column per source, the score of the chosen pair, then Link. */
@Component({
  selector: 'app-manual-match-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatchingEvidenceComponent],
  template: `
    <h2 mat-dialog-title>Link two entities manually</h2>
    <mat-dialog-content class="text-sm">
      <div class="flex flex-wrap items-center gap-4 mb-2">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-64">
          <mat-label>Type group</mat-label>
          <mat-select [(ngModel)]="typeGroupId" (selectionChange)="searchBoth()">
            <mat-option [value]="null">All</mat-option>
            <mat-option *ngFor="let g of data.typeGroups" [value]="g.id">{{ g.label }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-checkbox [(ngModel)]="unmatchedOnly" (change)="searchBoth()">Only entities without a counterpart in the other source</mat-checkbox>
      </div>
      <div class="grid grid-cols-2 gap-4">
        <div *ngFor="let side of sides; let i = index" class="border rounded-lg p-2 flex flex-col">
          <div class="font-semibold mb-1">{{ side.dataSource }}</div>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Search by name or IRI</mat-label>
            <input matInput [(ngModel)]="side.query" (ngModelChange)="side.search$.next()">
          </mat-form-field>
          <div class="mt-2 h-64 overflow-y-auto">
            <div *ngIf="side.loading" class="text-xs text-gray-500 p-2">Searching…</div>
            <button *ngFor="let e of side.results" type="button"
                    class="w-full text-left px-2 py-1 rounded hover:bg-blue-50"
                    [ngClass]="side.selected?.iri === e.node.iri ? 'bg-blue-100' : ''"
                    (click)="select(i, e.node)">
              <div class="font-medium">{{ name(e.node) }}</div>
              <div class="text-xs text-gray-500">
                {{ e.typeCuries.join(', ') }}
                <span *ngIf="e.matchedWith.length" class="ml-1 text-emerald-700">· linked with {{ e.matchedWith.length }}</span>
              </div>
            </button>
            <p *ngIf="!side.loading && !side.results.length" class="text-xs text-gray-500 italic p-2">No entity.</p>
          </div>
        </div>
      </div>

      <div *ngIf="comparison as c" class="mt-3 p-3 rounded-lg bg-gray-50 border">
        <div class="mb-2">
          <span class="font-semibold">{{ c.score != null ? (c.score * 100 | number:'1.0-0') + ' %' : 'Not comparable' }}</span>
          <span class="text-gray-500 ml-2">{{ c.typeGroupLabel || 'no shared type group' }}</span>
        </div>
        <app-matching-evidence [evidence]="c.evidence" [labelA]="sides[0].dataSource" [labelB]="sides[1].dataSource"></app-matching-evidence>
        <div *ngIf="c.blockingReasons.length" class="mt-2 p-2 rounded bg-amber-50 border border-amber-200 text-amber-800 text-xs">
          <p *ngFor="let r of c.blockingReasons">⚠ {{ r }}</p>
        </div>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-full mt-2">
          <mat-label>Note (optional)</mat-label>
          <input matInput [(ngModel)]="note">
        </mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close(changed)">Close</button>
      <button mat-flat-button color="primary" [disabled]="!canLink" (click)="link()">Link</button>
    </mat-dialog-actions>
  `
})
export class ManualMatchDialogComponent implements OnInit, OnDestroy {
  sides: SideState[];
  typeGroupId: string | null = null;
  unmatchedOnly = true;
  comparison: PairComparison | null = null;
  note = '';
  changed = false;
  name = nodeName;
  private destroy$ = new Subject<void>();

  constructor(public ref: MatDialogRef<ManualMatchDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: ManualMatchDialogData,
              private http: MatchingHttpService,
              private actions: MatchingActionsService) {
    this.sides = [
      this.newSide(data.dataSourceA, data.dataSourceB, data.prefillA ?? null),
      this.newSide(data.dataSourceB, data.dataSourceA, data.prefillB ?? null)
    ];
  }

  ngOnInit(): void {
    this.sides.forEach(side => {
      side.search$.pipe(
        debounceTime(250),
        switchMap(() => {
          side.loading = true;
          return this.http.entities(side.dataSource, side.query, this.typeGroupId,
            this.unmatchedOnly ? side.other : null).pipe(catchError(() => of([] as MatchableEntity[])));
        }),
        takeUntil(this.destroy$)
      ).subscribe(results => {
        side.results = results;
        side.loading = false;
      });
    });
    this.searchBoth();
    this.compare();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get canLink(): boolean {
    return !!this.sides[0].selected && !!this.sides[1].selected && !!this.comparison
      && this.comparison.blockingReasons.length === 0;
  }

  searchBoth(): void {
    this.sides.forEach(s => s.search$.next());
  }

  select(i: number, node: EntityNode): void {
    this.sides[i].selected = node;
    this.compare();
  }

  link(): void {
    const [a, b] = [this.sides[0].selected!, this.sides[1].selected!];
    this.actions.createManualLink({ dataSource: a.dataSource, iri: a.iri }, { dataSource: b.dataSource, iri: b.iri },
      this.note || null).subscribe(() => {
      this.changed = true;
      this.sides.forEach(s => s.selected = null);
      this.comparison = null;
      this.note = '';
      this.searchBoth();
    });
  }

  private compare(): void {
    const [a, b] = [this.sides[0].selected, this.sides[1].selected];
    this.comparison = null;
    if (!a || !b) return;
    this.http.compare(a.dataSource, a.iri, b.dataSource, b.iri).subscribe({
      next: c => this.comparison = c,
      error: err => this.comparison = {
        a: { node: a, properties: [] }, b: { node: b, properties: [] }, typeGroupId: null, typeGroupLabel: null,
        score: null, comparedWeight: null, proposable: false, outcome: null, evidence: [], conceptPredicates: {},
        existingLink: null, blockingReasons: [MatchingHttpService.errorMessage(err)]
      }
    });
  }

  private newSide(dataSource: string, other: string, selected: EntityNode | null): SideState {
    return {
      dataSource, other, query: selected?.label ?? '', results: [], selected,
      loading: false, search$: new Subject<void>()
    };
  }
}
