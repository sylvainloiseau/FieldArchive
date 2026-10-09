import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatchingHttpService } from '../../../services/matching-http.service';
import { MatchingActionsService } from '../../../services/matching-actions.service';
import { EntityDetailsDialogService } from '../../../services/entity-details-dialog.service';
import { EntityNode, LinkAction, PairComparison, PropertyValues, nodeName } from '../../../models/matching.model';

export interface ComparisonDialogData {
  a: EntityNode;
  b: EntityNode;
}

interface AlignedRow {
  concept: string;
  predicatesA: string[];
  predicatesB: string[];
  valuesA: string[];
  valuesB: string[];
  score: number | null;
}

/**
 * Two entities side by side: the compared concepts aligned on one line each (e.g. foaf:name |
 * rico:name), then the other properties of each entity in its own graph. Closes with true when
 * a link changed.
 */
@Component({
  selector: 'app-matching-comparison-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>Compare</h2>
    <mat-dialog-content class="text-sm">
      <div *ngIf="loading" class="flex justify-center p-8"><mat-spinner diameter="32"></mat-spinner></div>
      <div *ngIf="error" class="p-3 rounded bg-red-50 text-red-700">{{ error }}</div>

      <ng-container *ngIf="cmp">
        <div class="grid grid-cols-[10rem_1fr_6rem_1fr] gap-2 items-start mb-3">
          <div></div>
          <div class="font-semibold">
            {{ name(cmp.a.node) }}
            <span class="ml-1 px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-xs">{{ cmp.a.node.dataSource }}</span>
            <button class="ml-1 text-xs text-blue-600 underline" (click)="openEntity(cmp.a.node.iri)">open</button>
            <div class="text-xs text-gray-400 break-all font-normal">{{ cmp.a.node.iri }}</div>
          </div>
          <div class="text-center">
            <div *ngIf="cmp.score != null" class="text-lg font-bold" [ngClass]="cmp.proposable ? 'text-emerald-700' : 'text-gray-600'">
              {{ cmp.score * 100 | number:'1.0-0' }} %
            </div>
            <div class="text-xs text-gray-500">{{ cmp.typeGroupLabel || 'no shared type group' }}</div>
          </div>
          <div class="font-semibold">
            {{ name(cmp.b.node) }}
            <span class="ml-1 px-1.5 py-0.5 rounded bg-violet-50 text-violet-700 text-xs">{{ cmp.b.node.dataSource }}</span>
            <button class="ml-1 text-xs text-blue-600 underline" (click)="openEntity(cmp.b.node.iri)">open</button>
            <div class="text-xs text-gray-400 break-all font-normal">{{ cmp.b.node.iri }}</div>
          </div>
        </div>

        <div *ngIf="outcomeText" class="mb-2 text-xs text-gray-600">{{ outcomeText }}</div>
        <div *ngIf="cmp.blockingReasons.length" class="mb-3 p-2 rounded bg-amber-50 border border-amber-200 text-amber-800 text-xs">
          <p *ngFor="let r of cmp.blockingReasons">⚠ {{ r }}</p>
        </div>
        <div *ngIf="cmp.existingLink" class="mb-3 text-xs">
          Current link: <span class="font-semibold">{{ cmp.existingLink.status }}</span>
          <span *ngIf="cmp.existingLink.note" class="text-gray-500"> — {{ cmp.existingLink.note }}</span>
        </div>

        <h3 class="text-xs uppercase tracking-wide text-gray-500 mb-1">Compared concepts</h3>
        <div *ngFor="let r of aligned" class="grid grid-cols-[10rem_1fr_6rem_1fr] gap-2 py-1 border-t border-gray-100">
          <div>
            <div class="font-medium">{{ r.concept }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-400">{{ r.predicatesA.join(', ') || '—' }}</div>
            <div *ngFor="let v of r.valuesA" class="break-all">{{ v }}</div>
          </div>
          <div class="text-center text-xs" [ngClass]="r.score == null ? 'text-gray-400' : r.score >= 0.9 ? 'text-emerald-700' : r.score >= 0.6 ? 'text-amber-700' : 'text-red-600'">
            {{ r.score == null ? 'not compared' : (r.score * 100 | number:'1.0-0') + ' %' }}
          </div>
          <div>
            <div class="text-xs text-gray-400">{{ r.predicatesB.join(', ') || '—' }}</div>
            <div *ngFor="let v of r.valuesB" class="break-all">{{ v }}</div>
          </div>
        </div>
        <p *ngIf="!aligned.length" class="text-xs text-gray-500 italic">No concept of a shared type group.</p>

        <h3 class="text-xs uppercase tracking-wide text-gray-500 mt-4 mb-1">Other properties</h3>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <div *ngFor="let p of otherA" class="py-0.5">
              <span class="text-xs text-gray-500">{{ p.curie }}</span>
              <div *ngFor="let v of p.values" class="break-all">{{ v }}</div>
            </div>
          </div>
          <div>
            <div *ngFor="let p of otherB" class="py-0.5">
              <span class="text-xs text-gray-500">{{ p.curie }}</span>
              <div *ngFor="let v of p.values" class="break-all">{{ v }}</div>
            </div>
          </div>
        </div>
      </ng-container>
    </mat-dialog-content>
    <mat-dialog-actions align="end" *ngIf="cmp">
      <button mat-button (click)="ref.close(changed)">Close</button>
      <ng-container *ngIf="cmp.existingLink as l">
        <ng-container [ngSwitch]="l.status">
          <ng-container *ngSwitchCase="'PROPOSED'">
            <button mat-stroked-button color="warn" (click)="act('REJECT')">Reject</button>
            <button mat-flat-button color="primary" [disabled]="l.superseded" (click)="act('ACCEPT')">Accept</button>
          </ng-container>
          <ng-container *ngSwitchCase="'REJECTED'">
            <button mat-stroked-button (click)="act('UNDO')">Undo rejection</button>
            <button mat-flat-button color="primary" [disabled]="cmp.blockingReasons.length > 0" (click)="link()">Link anyway</button>
          </ng-container>
          <ng-container *ngSwitchCase="'INFERRED'"></ng-container>
          <ng-container *ngSwitchDefault>
            <button mat-stroked-button color="warn" (click)="act('REJECT')">Reject</button>
            <button mat-stroked-button (click)="act('UNDO')">Undo</button>
          </ng-container>
        </ng-container>
      </ng-container>
      <button *ngIf="!cmp.existingLink" mat-flat-button color="primary"
              [disabled]="cmp.blockingReasons.length > 0" (click)="link()">Link</button>
    </mat-dialog-actions>
  `
})
export class MatchingComparisonDialogComponent implements OnInit {
  cmp: PairComparison | null = null;
  aligned: AlignedRow[] = [];
  otherA: PropertyValues[] = [];
  otherB: PropertyValues[] = [];
  loading = true;
  error: string | null = null;
  changed = false;
  name = nodeName;

  constructor(public ref: MatDialogRef<MatchingComparisonDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: ComparisonDialogData,
              private http: MatchingHttpService,
              private actions: MatchingActionsService,
              private entityDialog: EntityDetailsDialogService) {}

  ngOnInit(): void {
    this.load();
  }

  get outcomeText(): string | null {
    switch (this.cmp?.outcome) {
      case 'PROPOSABLE': return 'Above the threshold, with enough evidence: the algorithm proposes this pair.';
      case 'BELOW_THRESHOLD': return 'Below the threshold: the algorithm would not propose this pair.';
      case 'INSUFFICIENT_EVIDENCE': return 'Not enough comparable properties (or a required one is missing) to propose this pair.';
      case 'NO_SHARED_GROUP': return 'The two entities share no type group: the algorithm never compares them.';
      default: return null;
    }
  }

  private load(): void {
    this.loading = true;
    this.http.compare(this.data.a.dataSource, this.data.a.iri, this.data.b.dataSource, this.data.b.iri).subscribe({
      next: cmp => {
        this.cmp = cmp;
        this.buildRows(cmp);
        this.loading = false;
      },
      error: err => {
        this.error = MatchingHttpService.errorMessage(err);
        this.loading = false;
      }
    });
  }

  private buildRows(cmp: PairComparison): void {
    const byPredA = new Map(cmp.a.properties.map(p => [p.predicate, p]));
    const byPredB = new Map(cmp.b.properties.map(p => [p.predicate, p]));
    const used = new Set<string>();
    this.aligned = Object.entries(cmp.conceptPredicates).map(([concept, [predsA, predsB]]) => {
      predsA.forEach(p => used.add('A' + p));
      predsB.forEach(p => used.add('B' + p));
      const ev = cmp.evidence.find(e => e.concept === concept);
      return {
        concept,
        predicatesA: predsA.map(p => byPredA.get(p)?.curie ?? p),
        predicatesB: predsB.map(p => byPredB.get(p)?.curie ?? p),
        valuesA: predsA.flatMap(p => byPredA.get(p)?.values ?? []),
        valuesB: predsB.flatMap(p => byPredB.get(p)?.values ?? []),
        score: ev ? ev.score : null
      };
    });
    this.otherA = cmp.a.properties.filter(p => !used.has('A' + p.predicate));
    this.otherB = cmp.b.properties.filter(p => !used.has('B' + p.predicate));
  }

  act(action: LinkAction): void {
    const link = this.cmp?.existingLink;
    if (!link) return;
    this.actions.applyAction(link, action).subscribe(() => {
      this.changed = true;
      this.load();
    });
  }

  link(): void {
    this.actions.createManualLink(this.data.a, this.data.b).subscribe(() => {
      this.changed = true;
      this.load();
    });
  }

  openEntity(iri: string): void {
    this.entityDialog.openLoadingTypes(iri).subscribe();
  }
}
