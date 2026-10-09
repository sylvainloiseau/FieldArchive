import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PairOverview, STALE_REASON_LABELS } from '../../../models/matching.model';

/** Every unordered pair of data sources, with its counts and whether its proposals are out of date. */
@Component({
  selector: 'app-matching-pair-list',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    <div class="flex flex-col gap-1">
      <button *ngFor="let p of pairs" type="button"
              class="text-left px-3 py-2 rounded-lg border transition-colors"
              [ngClass]="p.key === selectedKey ? 'bg-blue-50 border-blue-300' : 'bg-white border-gray-200 hover:bg-gray-50'"
              (click)="select.emit(p)">
        <div class="flex items-center gap-1 text-sm font-medium">
          <span class="truncate">{{ p.dataSourceA }}</span>
          <span class="text-gray-400">⇄</span>
          <span class="truncate">{{ p.dataSourceB }}</span>
          <span class="flex-1"></span>
          <span *ngIf="p.stale" class="text-amber-500" [matTooltip]="staleTooltip(p)">⟳</span>
        </div>
        <div class="flex flex-wrap gap-2 text-xs mt-0.5">
          <span *ngIf="!p.lastRun && !hasLinks(p)" class="text-gray-400">never run</span>
          <span *ngIf="p.counts['toReview']" class="text-blue-700" matTooltip="To review">▲ {{ p.counts['toReview'] }}</span>
          <span *ngIf="confirmed(p)" class="text-emerald-700" matTooltip="Accepted or manual">✓ {{ confirmed(p) }}</span>
          <span *ngIf="p.counts['INFERRED']" class="text-sky-700" matTooltip="Inferred">≡ {{ p.counts['INFERRED'] }}</span>
          <span *ngIf="p.counts['REJECTED']" class="text-red-600" matTooltip="Rejected">✗ {{ p.counts['REJECTED'] }}</span>
          <span *ngIf="p.counts['orphaned']" class="text-gray-500" matTooltip="Links to entities that no longer exist">⚠ {{ p.counts['orphaned'] }}</span>
        </div>
      </button>
      <p *ngIf="!pairs.length" class="text-sm text-gray-500 italic p-2">At least two data sources are needed.</p>
    </div>
  `
})
export class MatchingPairListComponent {
  @Input() pairs: PairOverview[] = [];
  @Input() selectedKey: string | null = null;
  @Output() select = new EventEmitter<PairOverview>();

  confirmed(p: PairOverview): number {
    return (p.counts['ACCEPTED'] ?? 0) + (p.counts['MANUAL'] ?? 0);
  }

  hasLinks(p: PairOverview): boolean {
    return Object.values(p.counts).some(v => v > 0);
  }

  staleTooltip(p: PairOverview): string {
    return 'To rerun: ' + p.staleReasons.map(r => STALE_REASON_LABELS[r]).join('; ');
  }
}
