import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatchingHttpService } from '../../services/matching-http.service';
import { MatchingSettingsHttpService } from '../../services/matching-settings-http.service';
import { MatchingOverview, PairOverview, nodeName } from '../../models/matching.model';
import { MatchingPairListComponent } from './matching-pair-list/matching-pair-list.component';
import { MatchingReviewPanelComponent, ReviewTab } from './matching-review-panel/matching-review-panel.component';
import { MatchingClustersDialogComponent } from './matching-clusters-dialog/matching-clusters-dialog.component';

/**
 * The data matching page: the pairs of data sources on the left, the review of the selected
 * pair on the right. The selection is kept in the query parameters (?a=&b=&tab=).
 */
@Component({
  selector: 'app-data-matching',
  standalone: true,
  imports: [CommonModule, RouterModule, MatButtonModule, MatProgressBarModule, MatchingPairListComponent,
    MatchingReviewPanelComponent],
  template: `
    <div class="flex flex-col h-full bg-gray-50">
      <div class="px-6 pt-4 pb-2 flex items-center gap-3">
        <button mat-button routerLink="/gestion-sources">← Data sources</button>
        <h1 class="text-xl font-semibold">Data matching</h1>
        <span class="flex-1"></span>
        <button mat-stroked-button (click)="openClusters()">Linked across sources</button>
        <button mat-stroked-button routerLink="/data-matching/settings">⚙ Settings</button>
      </div>
      <mat-progress-bar *ngIf="loading" mode="indeterminate"></mat-progress-bar>

      <div class="px-6 space-y-2">
        <div *ngIf="overview?.configurationError" class="p-3 rounded-lg bg-red-50 border border-red-200 text-red-800 text-sm">
          {{ overview?.configurationError }} <a routerLink="/data-matching/settings" class="underline">Open the settings</a>
        </div>
        <div *ngIf="overview?.fileWarning" class="p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-800 text-sm">
          ⚠ {{ overview?.fileWarning }}
        </div>
        <div *ngIf="overview?.conflicts?.length" class="p-3 rounded-lg bg-red-50 border border-red-200 text-red-800 text-sm">
          <div class="font-medium">⚠ {{ overview!.conflicts.length }} conflict(s) between the links</div>
          <div *ngFor="let c of overview!.conflicts" class="mt-1">
            {{ c.message }}
            <span class="text-red-600">({{ conflictNodes(c.nodes) }})</span>
          </div>
        </div>
      </div>

      <div class="flex flex-1 overflow-hidden gap-4 p-6 pt-3">
        <aside class="w-80 flex flex-col overflow-y-auto">
          <h2 class="text-xs uppercase tracking-wide text-gray-500 mb-2">Source pairs</h2>
          <app-matching-pair-list [pairs]="overview?.pairs ?? []" [selectedKey]="selected?.key ?? null"
                                  (select)="selectPair($event)"></app-matching-pair-list>
          <button mat-flat-button color="primary" class="mt-3" [disabled]="runningAll || (overview?.pairs?.length ?? 0) === 0"
                  (click)="runAll()">▶ Run all pairs</button>
          <p class="text-xs text-gray-500 mt-3">
            ▲ to review · ✓ confirmed · ≡ inferred · ✗ rejected · ⚠ orphaned · ⟳ to rerun
          </p>
        </aside>
        <section class="flex-1 overflow-hidden bg-white rounded-xl border p-4 border-gray-200">
          <app-matching-review-panel *ngIf="selected; else pick" [pair]="selected" [typeGroups]="typeGroups"
                                     [initialTab]="initialTab" (changed)="refresh()" (tabChange)="onTab($event)">
          </app-matching-review-panel>
          <ng-template #pick>
            <p class="text-sm text-gray-500 p-6">Select a pair of data sources.</p>
          </ng-template>
        </section>
      </div>
    </div>
  `
})
export class DataMatchingComponent implements OnInit {
  overview: MatchingOverview | null = null;
  selected: PairOverview | null = null;
  typeGroups: { id: string; label: string }[] = [];
  initialTab: ReviewTab = 'toReview';
  loading = false;
  runningAll = false;

  constructor(private http: MatchingHttpService,
              private settings: MatchingSettingsHttpService,
              private route: ActivatedRoute,
              private router: Router,
              private dialog: MatDialog,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const tab = params.get('tab') as ReviewTab | null;
    if (tab) this.initialTab = tab;
    this.refresh(params.get('a'), params.get('b'));
    this.settings.get().subscribe({
      next: env => this.typeGroups = env.configuration.equivalentGroups.map(g => ({ id: g.id, label: g.groupLabel })),
      error: () => this.typeGroups = []
    });
  }

  refresh(a: string | null = null, b: string | null = null): void {
    this.loading = true;
    this.http.overview().subscribe({
      next: o => {
        this.overview = o;
        this.loading = false;
        const wantA = a ?? this.selected?.dataSourceA, wantB = b ?? this.selected?.dataSourceB;
        const match = o.pairs.find(p => (p.dataSourceA === wantA && p.dataSourceB === wantB)
          || (p.dataSourceA === wantB && p.dataSourceB === wantA));
        this.selected = match ?? this.selected ?? null;
        if (match) this.selected = match;
      },
      error: err => {
        this.loading = false;
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
      }
    });
  }

  selectPair(p: PairOverview): void {
    this.selected = p;
    this.router.navigate([], { queryParams: { a: p.dataSourceA, b: p.dataSourceB }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  onTab(tab: ReviewTab): void {
    this.router.navigate([], { queryParams: { tab }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  runAll(): void {
    this.runningAll = true;
    this.http.runAll().subscribe({
      next: summaries => {
        this.runningAll = false;
        const total = summaries.reduce((n, s) => n + s.proposalCount, 0);
        this.snackBar.open(`${summaries.length} pair(s) run, ${total} proposal(s)`, 'Close', { duration: 5000 });
        this.refresh();
      },
      error: err => {
        this.runningAll = false;
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
      }
    });
  }

  openClusters(): void {
    this.dialog.open(MatchingClustersDialogComponent, { width: '800px', maxWidth: '95vw', maxHeight: '90vh' });
  }

  conflictNodes(nodes: { dataSource: string; iri: string; label?: string | null }[]): string {
    return nodes.map(n => `${n.dataSource}: ${nodeName(n)}`).join(', ');
  }
}
