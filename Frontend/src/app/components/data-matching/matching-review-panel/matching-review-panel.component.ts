import { Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import { MatchingHttpService } from '../../../services/matching-http.service';
import { MatchingActionsService } from '../../../services/matching-actions.service';
import { EntityDetailsDialogService } from '../../../services/entity-details-dialog.service';
import {
  LinkAction, MatchLink, MatchStatus, PairOverview, STALE_REASON_LABELS, nodeName
} from '../../../models/matching.model';
import { MatchingEvidenceComponent } from '../matching-evidence/matching-evidence.component';
import { MatchingComparisonDialogComponent } from '../matching-comparison-dialog/matching-comparison-dialog.component';
import { ManualMatchDialogComponent } from '../manual-match-dialog/manual-match-dialog.component';

export type ReviewTab = 'toReview' | 'ACCEPTED' | 'MANUAL' | 'INFERRED' | 'REJECTED' | 'all';

/** The links of one pair of data sources: tabs by status, filters, evidence, actions, keyboard review. */
@Component({
  selector: 'app-matching-review-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSliderModule, MatTooltipModule, MatProgressBarModule, MatchingEvidenceComponent],
  template: `
    <div class="flex flex-col h-full">
      <!-- header -->
      <div class="flex items-start gap-3 mb-3">
        <div class="flex-1">
          <h2 class="text-lg font-semibold">{{ pair.dataSourceA }} <span class="text-gray-400">⇄</span> {{ pair.dataSourceB }}</h2>
          <p class="text-xs text-gray-500" *ngIf="pair.lastRun as r">
            Last run {{ r.startedAt | date:'short' }} · {{ r.proposalCount }} proposal(s) ·
            {{ r.comparedPairCount }} pair(s) compared in {{ r.durationMs }} ms
            <span *ngIf="r.identicalIriCount"> · {{ r.identicalIriCount }} identical IRI(s)</span>
          </p>
          <p class="text-xs text-gray-500" *ngIf="!pair.lastRun">Never run.</p>
          <p class="text-xs text-amber-700 mt-0.5" *ngIf="pair.stale && pair.lastRun">
            ⚠ To rerun: {{ staleText }}
          </p>
        </div>
        <button mat-stroked-button *ngIf="pair.counts['orphaned']" (click)="removeOrphaned()"
                matTooltip="Links whose entity no longer exists in its source">
          Remove {{ pair.counts['orphaned'] }} orphaned
        </button>
        <button mat-flat-button color="primary" [disabled]="running" (click)="runPair()">▶ Run matching</button>
      </div>
      <mat-progress-bar *ngIf="running || loading" mode="indeterminate" class="mb-2"></mat-progress-bar>

      <!-- tabs -->
      <div class="flex flex-wrap gap-1 border-b border-gray-200 mb-2">
        <button *ngFor="let t of tabs" type="button" (click)="setTab(t.id)"
                class="px-3 py-1.5 text-sm -mb-px border-b-2"
                [ngClass]="tab === t.id ? 'border-blue-600 text-blue-700 font-medium' : 'border-transparent text-gray-600 hover:text-gray-900'">
          {{ t.label }} <span class="text-xs text-gray-400" *ngIf="tabCount(t.id) != null">{{ tabCount(t.id) }}</span>
        </button>
      </div>

      <!-- filters -->
      <div class="flex flex-wrap items-center gap-3 mb-2">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-56">
          <mat-label>Type group</mat-label>
          <mat-select [(ngModel)]="typeGroupId" (selectionChange)="reload()">
            <mat-option [value]="null">All</mat-option>
            <mat-option *ngFor="let g of typeGroups" [value]="g.id">{{ g.label }}</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="flex items-center gap-2 text-sm" *ngIf="tab === 'toReview' || tab === 'all'">
          <span class="text-gray-600">Score ≥ {{ minScore }} %</span>
          <mat-slider min="0" max="100" step="5" class="w-40">
            <input matSliderThumb [(ngModel)]="minScore" (ngModelChange)="filter$.next()">
          </mat-slider>
        </div>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-64">
          <mat-label>Search</mat-label>
          <input matInput [(ngModel)]="q" (ngModelChange)="filter$.next()">
        </mat-form-field>
        <span class="flex-1"></span>
        <span class="text-xs text-gray-400" matTooltip="j/k or ↓/↑ move · a accept · r reject · u undo · space details · c compare · m manual link">⌨ keys</span>
      </div>

      <!-- list -->
      <div #list class="flex-1 overflow-y-auto border rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-blue-200 border-gray-200"
           tabindex="0" (keydown)="onKey($event)">
        <div class="grid grid-cols-[1fr_1fr_9rem_9rem] gap-2 px-3 py-2 text-xs font-medium text-gray-500 border-b sticky top-0 bg-white z-10 border-gray-200">
          <div>{{ pair.dataSourceA }}</div><div>{{ pair.dataSourceB }}</div><div>Score</div><div class="text-right">Actions</div>
        </div>
        <div *ngFor="let l of links; let i = index" [attr.data-row]="i"
             class="border-b border-gray-100" [ngClass]="i === cursor ? 'bg-blue-50' : ''">
          <div class="grid grid-cols-[1fr_1fr_9rem_9rem] gap-2 px-3 py-2 items-center cursor-pointer" (click)="cursor = i; toggle(l)">
            <div class="min-w-0">
              <button type="button" class="font-medium text-left hover:underline truncate max-w-full" (click)="$event.stopPropagation(); openEntity(l.a.iri)"
                      [ngClass]="l.orphanA ? 'text-gray-400 line-through' : ''">{{ name(l.a) }}</button>
              <div class="text-xs text-gray-400 truncate">{{ l.a.iri }}</div>
            </div>
            <div class="min-w-0">
              <button type="button" class="font-medium text-left hover:underline truncate max-w-full" (click)="$event.stopPropagation(); openEntity(l.b.iri)"
                      [ngClass]="l.orphanB ? 'text-gray-400 line-through' : ''">{{ name(l.b) }}</button>
              <div class="text-xs text-gray-400 truncate">{{ l.b.iri }}</div>
            </div>
            <div>
              <div *ngIf="l.score != null" class="flex items-center gap-1">
                <div class="h-2 w-16 bg-gray-200 rounded">
                  <div class="h-2 rounded" [ngClass]="l.score >= 0.9 ? 'bg-emerald-500' : l.score >= 0.75 ? 'bg-amber-400' : 'bg-red-400'"
                       [style.width.%]="l.score * 100"></div>
                </div>
                <span class="text-xs">{{ l.score * 100 | number:'1.0-0' }} %</span>
              </div>
              <div class="flex flex-wrap gap-1 mt-0.5 text-[10px]">
                <span class="px-1 rounded" [ngClass]="statusClass(l.status)">{{ l.status.toLowerCase() }}</span>
                <span *ngIf="l.typeGroupLabel" class="px-1 rounded bg-gray-100 text-gray-600">{{ l.typeGroupLabel }}</span>
                <span *ngIf="l.superseded" class="px-1 rounded bg-gray-200 text-gray-600" matTooltip="One of these entities already has a counterpart, or accepting would contradict other links">superseded</span>
                <span *ngIf="l.typeMismatch" class="px-1 rounded bg-amber-100 text-amber-700" matTooltip="The two entities share no type group">type mismatch</span>
                <span *ngIf="l.orphanA || l.orphanB" class="px-1 rounded bg-gray-200 text-gray-600" matTooltip="An entity no longer exists in its source">orphaned</span>
                <span *ngIf="l.conflict" class="px-1 rounded bg-red-100 text-red-700">conflict</span>
                <span *ngIf="l.note" class="px-1 rounded bg-yellow-100 text-yellow-800" [matTooltip]="l.note">note</span>
              </div>
            </div>
            <div class="flex justify-end gap-1" (click)="$event.stopPropagation()">
              <ng-container [ngSwitch]="l.status">
                <ng-container *ngSwitchCase="'PROPOSED'">
                  <button class="act text-emerald-700" [disabled]="l.superseded" matTooltip="Accept (a)" (click)="act(l, 'ACCEPT')">✓</button>
                  <button class="act text-red-600" matTooltip="Reject (r)" (click)="act(l, 'REJECT')">✗</button>
                </ng-container>
                <ng-container *ngSwitchCase="'INFERRED'"></ng-container>
                <ng-container *ngSwitchDefault>
                  <button class="act text-gray-600" matTooltip="Undo (u)" (click)="act(l, 'UNDO')">↺</button>
                  <button *ngIf="l.status !== 'REJECTED'" class="act text-red-600" matTooltip="Reject (r)" (click)="act(l, 'REJECT')">✗</button>
                </ng-container>
              </ng-container>
              <button class="act text-blue-700" matTooltip="Compare side by side (c)" (click)="compare(l)">⇆</button>
            </div>
          </div>

          <!-- details -->
          <div *ngIf="expanded.has(l.id)" class="px-3 pb-3 pt-1 bg-gray-50">
            <ng-container *ngIf="l.status === 'INFERRED'; else evidenceTpl">
              <p class="text-xs text-gray-600 mb-1">Inferred from:</p>
              <div class="text-sm flex flex-wrap items-center gap-1">
                <ng-container *ngFor="let s of l.inferenceSteps; let first = first">
                  <span *ngIf="first" class="px-1.5 py-0.5 rounded bg-white border border-gray-200">{{ name(s.from) }} <span class="text-xs text-gray-500">{{ s.from.dataSource }}</span></span>
                  <span class="text-xs text-gray-500">{{ s.kind === 'identical' ? '= same IRI' : '≡ ' + (s.status || '').toLowerCase() }}</span>
                  <span class="px-1.5 py-0.5 rounded bg-white border border-gray-200">{{ name(s.to) }} <span class="text-xs text-gray-500">{{ s.to.dataSource }}</span></span>
                </ng-container>
              </div>
              <p class="text-xs text-gray-500 mt-1">To break it, undo one of these links.</p>
            </ng-container>
            <ng-template #evidenceTpl>
              <app-matching-evidence [evidence]="l.evidence" [labelA]="pair.dataSourceA" [labelB]="pair.dataSourceB"></app-matching-evidence>
            </ng-template>
            <div class="flex items-center gap-2 mt-2">
              <input class="flex-1 text-sm px-2 py-1 border rounded border-gray-200" placeholder="Note" [(ngModel)]="noteDrafts[l.id]"
                     (keydown)="$event.stopPropagation()" (keydown.enter)="saveNote(l)">
              <button mat-stroked-button (click)="saveNote(l)" [disabled]="(noteDrafts[l.id] || '') === (l.note || '')">Save note</button>
              <button mat-stroked-button *ngIf="l.status === 'PROPOSED'" (click)="manual(l)">None of these: link manually…</button>
            </div>
          </div>
        </div>
        <p *ngIf="!loading && !links.length" class="p-6 text-center text-sm text-gray-500">
          {{ tab === 'toReview' ? (pair.lastRun ? 'Nothing left to review.' : 'Run the matching to get proposals.') : 'No link.' }}
        </p>
        <p *ngIf="total > links.length" class="p-2 text-center text-xs text-gray-500">
          {{ links.length }} of {{ total }} shown — narrow the filters to see the others.
        </p>
      </div>

      <!-- footer -->
      <div class="flex items-center gap-2 mt-2">
        <button mat-stroked-button (click)="manual(null)">+ Manual link</button>
        <span class="flex-1"></span>
        <ng-container *ngIf="tab === 'toReview'">
          <span class="text-sm text-gray-600">Accept all ≥</span>
          <input type="number" min="50" max="100" step="1" class="w-16 px-2 py-1 border rounded text-sm border-gray-200" [(ngModel)]="bulkThreshold">
          <span class="text-sm text-gray-600">%</span>
          <button mat-stroked-button color="primary" [disabled]="!bulkCandidates.length" (click)="acceptAllAbove()">
            Accept {{ bulkCandidates.length }}
          </button>
        </ng-container>
      </div>
    </div>
  `
})
export class MatchingReviewPanelComponent implements OnInit, OnChanges, OnDestroy {
  @Input({ required: true }) pair!: PairOverview;
  @Input() typeGroups: { id: string; label: string }[] = [];
  @Input() initialTab: ReviewTab = 'toReview';
  /** A run or a link change happened: the page refreshes the overview. */
  @Output() changed = new EventEmitter<void>();
  @Output() tabChange = new EventEmitter<ReviewTab>();
  @ViewChild('list') listRef!: ElementRef<HTMLElement>;

  readonly tabs: { id: ReviewTab; label: string }[] = [
    { id: 'toReview', label: 'To review' },
    { id: 'ACCEPTED', label: 'Accepted' },
    { id: 'MANUAL', label: 'Manual' },
    { id: 'INFERRED', label: 'Inferred' },
    { id: 'REJECTED', label: 'Rejected' },
    { id: 'all', label: 'All' }
  ];

  tab: ReviewTab = 'toReview';
  typeGroupId: string | null = null;
  minScore = 0;
  q = '';
  links: MatchLink[] = [];
  total = 0;
  cursor = 0;
  loading = false;
  running = false;
  expanded = new Set<string>();
  noteDrafts: Record<string, string> = {};
  bulkThreshold = 95;
  name = nodeName;
  readonly filter$ = new Subject<void>();
  private destroy$ = new Subject<void>();

  constructor(private http: MatchingHttpService,
              private actions: MatchingActionsService,
              private dialog: MatDialog,
              private snackBar: MatSnackBar,
              private entityDialog: EntityDetailsDialogService) {}

  ngOnInit(): void {
    this.filter$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe(() => this.reload());
    this.actions.changed$.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.reload();
      this.changed.emit();
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    // runs before ngOnInit: the first load must already use the initial tab
    if (changes['initialTab']?.firstChange) this.tab = this.initialTab;
    const previous = changes['pair']?.previousValue as PairOverview | undefined;
    if (changes['pair'] && (!previous || previous.key !== this.pair.key)) {
      this.cursor = 0;
      this.expanded.clear();
      this.reload();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get staleText(): string {
    return this.pair.staleReasons.map(r => STALE_REASON_LABELS[r]).join('; ');
  }

  get bulkCandidates(): MatchLink[] {
    const t = this.bulkThreshold / 100;
    return this.links.filter(l => l.status === 'PROPOSED' && !l.superseded && (l.score ?? 0) >= t);
  }

  tabCount(tab: ReviewTab): number | null {
    const c = this.pair.counts;
    switch (tab) {
      case 'toReview': return c['toReview'] ?? 0;
      case 'all': return null;
      default: return c[tab] ?? 0;
    }
  }

  setTab(tab: ReviewTab): void {
    this.tab = tab;
    this.cursor = 0;
    this.tabChange.emit(tab);
    this.reload();
  }

  reload(): void {
    if (!this.pair) return;
    const statuses: MatchStatus[] = this.tab === 'toReview' ? ['PROPOSED']
      : this.tab === 'all' ? [] : [this.tab];
    this.loading = true;
    this.http.links({
      dataSourceA: this.pair.dataSourceA,
      dataSourceB: this.pair.dataSourceB,
      status: statuses,
      typeGroupId: this.typeGroupId,
      minScore: this.minScore > 0 ? this.minScore / 100 : null,
      q: this.q,
      includeSuperseded: this.tab === 'all',
      limit: 500
    }).subscribe({
      next: page => {
        this.links = page.items;
        this.total = page.total;
        this.noteDrafts = {};
        page.items.forEach(l => this.noteDrafts[l.id] = l.note ?? '');
        this.cursor = Math.min(this.cursor, Math.max(0, this.links.length - 1));
        this.loading = false;
      },
      error: err => {
        this.loading = false;
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 6000 });
      }
    });
  }

  runPair(): void {
    this.running = true;
    this.http.run(this.pair.dataSourceA, this.pair.dataSourceB).subscribe({
      next: ([summary]) => {
        this.running = false;
        this.snackBar.open(`${summary.proposalCount} proposal(s), ${summary.comparedPairCount} pair(s) compared`, 'Close', { duration: 4000 });
        this.setTab('toReview');
        this.changed.emit();
      },
      error: err => {
        this.running = false;
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
      }
    });
  }

  act(link: MatchLink, action: LinkAction): void {
    if (link.status === 'INFERRED') return;
    if (action === 'ACCEPT' && (link.superseded || link.status !== 'PROPOSED')) return;
    if (action === 'UNDO' && link.status === 'PROPOSED') return;
    this.actions.applyAction(link, action).subscribe();
  }

  acceptAllAbove(): void {
    this.actions.applyBulk(this.bulkCandidates.map(l => l.id), 'ACCEPT').subscribe();
  }

  removeOrphaned(): void {
    this.actions.removeOrphaned(this.pair.key, null).subscribe();
  }

  saveNote(link: MatchLink): void {
    this.http.updateNote(link.id, this.noteDrafts[link.id] || null).subscribe({
      next: () => {
        link.note = this.noteDrafts[link.id] || null;
        this.snackBar.open('Note saved.', 'Close', { duration: 2000 });
      },
      error: err => this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 6000 })
    });
  }

  toggle(link: MatchLink): void {
    if (this.expanded.has(link.id)) this.expanded.delete(link.id); else this.expanded.add(link.id);
  }

  compare(link: MatchLink): void {
    this.dialog.open(MatchingComparisonDialogComponent, {
      width: '1100px', maxWidth: '95vw', maxHeight: '90vh',
      data: { a: link.a, b: link.b }
    }).afterClosed().subscribe(changed => { if (changed) { this.reload(); this.changed.emit(); } });
  }

  manual(link: MatchLink | null): void {
    this.dialog.open(ManualMatchDialogComponent, {
      width: '1000px', maxWidth: '95vw', maxHeight: '90vh',
      data: {
        dataSourceA: this.pair.dataSourceA,
        dataSourceB: this.pair.dataSourceB,
        typeGroups: this.typeGroups,
        prefillA: link?.a ?? null
      }
    }).afterClosed().subscribe(changed => { if (changed) { this.reload(); this.changed.emit(); } });
  }

  openEntity(iri: string): void {
    this.entityDialog.openLoadingTypes(iri).subscribe();
  }

  statusClass(status: MatchStatus): string {
    switch (status) {
      case 'ACCEPTED': case 'MANUAL': return 'bg-emerald-100 text-emerald-700';
      case 'REJECTED': return 'bg-red-100 text-red-700';
      case 'INFERRED': return 'bg-sky-100 text-sky-700';
      default: return 'bg-blue-100 text-blue-700';
    }
  }

  /** Keyboard review, active while the list has the focus. */
  onKey(event: KeyboardEvent): void {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const link = this.links[this.cursor];
    switch (event.key) {
      case 'j': case 'ArrowDown': this.move(1); break;
      case 'k': case 'ArrowUp': this.move(-1); break;
      case ' ': if (link) this.toggle(link); break;
      case 'a': if (link) this.act(link, 'ACCEPT'); break;
      case 'r': if (link) this.act(link, 'REJECT'); break;
      case 'u': if (link) this.act(link, 'UNDO'); break;
      case 'c': if (link) this.compare(link); break;
      case 'm': if (link) this.manual(link); break;
      default: return;
    }
    event.preventDefault();
  }

  private move(delta: number): void {
    if (!this.links.length) return;
    this.cursor = Math.max(0, Math.min(this.links.length - 1, this.cursor + delta));
    const row = this.listRef?.nativeElement.querySelector(`[data-row="${this.cursor}"]`);
    row?.scrollIntoView({ block: 'nearest' });
  }
}
