import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subscription } from 'rxjs';
import { MatchingSettingsHttpService } from '../../../services/matching-settings-http.service';
import { MatchingHttpService } from '../../../services/matching-http.service';
import { MatchingConfiguration, MatchingPreview, PreviewSample, nodeName } from '../../../models/matching.model';

/**
 * Runs the unsaved configuration on one pair of sources (nothing is written) and compares the
 * result with the current proposals and with the decisions already taken.
 */
@Component({
  selector: 'app-matching-preview-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatProgressBarModule],
  template: `
    <div class="flex flex-wrap items-center gap-3">
      <span class="text-sm font-medium">Preview on</span>
      <select class="border rounded px-2 py-1 text-sm border-gray-200" [(ngModel)]="pairKey">
        <option *ngFor="let p of pairs" [value]="p.key">{{ p.a }} ⇄ {{ p.b }}</option>
      </select>
      <button mat-stroked-button color="primary" [disabled]="!pairKey || running" (click)="run()">▶ Preview</button>
      <button mat-button *ngIf="running" (click)="cancel()">Cancel</button>
      <span class="text-xs text-gray-500">Runs the settings being edited; nothing is saved.</span>
    </div>
    <mat-progress-bar *ngIf="running" mode="indeterminate" class="mt-2"></mat-progress-bar>
    <p *ngIf="error" class="mt-2 text-sm text-red-700">{{ error }}</p>

    <div *ngIf="result as r" class="mt-3 grid grid-cols-1 lg:grid-cols-3 gap-4 text-sm">
      <div>
        <div class="font-medium mb-1">Proposals</div>
        <p>{{ r.summary.proposalCount }} proposal(s):
          <span class="text-emerald-700">+{{ r.proposalDiff.added }}</span>
          <span class="text-red-600 ml-1">−{{ r.proposalDiff.removed }}</span>
          <span class="text-gray-500 ml-1">{{ r.proposalDiff.unchanged }} unchanged</span>
        </p>
        <p class="text-xs text-gray-500">{{ r.summary.comparedPairCount }} pair(s) compared ·
          {{ r.summary.insufficientEvidenceCount }} without enough evidence · {{ r.summary.belowThresholdCount }} below the threshold</p>
        <button *ngIf="r.proposalDiff.samplesAdded.length || r.proposalDiff.samplesRemoved.length" type="button"
                class="text-xs text-blue-700 mt-1" (click)="showDiff = !showDiff">
          {{ showDiff ? 'Hide' : 'Show' }} new / lost proposals
        </button>
        <div *ngIf="showDiff" class="mt-1 text-xs space-y-0.5">
          <div *ngFor="let s of r.proposalDiff.samplesAdded" class="text-emerald-700">+ {{ sample(s) }}</div>
          <div *ngFor="let s of r.proposalDiff.samplesRemoved" class="text-red-600">− {{ sample(s) }}</div>
        </div>
      </div>
      <div>
        <div class="font-medium mb-1">Your past decisions</div>
        <p>Confirmed links found again: <b>{{ r.decisionAgreement.confirmedAbove }}</b>
          / {{ r.decisionAgreement.confirmedAbove + r.decisionAgreement.confirmedBelow }}</p>
        <p>Rejected pairs that would be proposed: <b [ngClass]="r.decisionAgreement.rejectedAbove ? 'text-red-600' : ''">{{ r.decisionAgreement.rejectedAbove }}</b>
          / {{ r.decisionAgreement.rejectedAbove + r.decisionAgreement.rejectedBelow }}</p>
        <div class="mt-1 text-xs space-y-0.5">
          <div *ngFor="let s of r.decisionAgreement.samples" class="text-gray-600">
            {{ s.status === 'REJECTED' ? 'rejected, would be proposed:' : 'confirmed, not found:' }} {{ sample(s) }}
          </div>
        </div>
      </div>
      <div>
        <div class="font-medium mb-1">Scores of the compared pairs</div>
        <div class="relative flex items-end gap-px h-20 border-b border-gray-300">
          <div *ngFor="let n of r.scoreHistogram; let i = index" class="flex-1 rounded-t"
               [ngClass]="i / 20 >= threshold ? 'bg-emerald-400' : 'bg-gray-300'"
               [style.height.%]="max(r) ? (n / max(r)) * 100 : 0" [title]="(i * 5) + '–' + (i * 5 + 5) + ' %: ' + n"></div>
          <div class="absolute top-0 bottom-0 border-l-2 border-dashed border-red-400" [style.left.%]="threshold * 100"></div>
        </div>
        <div class="flex justify-between text-[10px] text-gray-500"><span>0 %</span><span>threshold {{ threshold * 100 | number:'1.0-0' }} %</span><span>100 %</span></div>
      </div>
    </div>
  `
})
export class MatchingPreviewPanelComponent implements OnChanges {
  @Input({ required: true }) configuration!: MatchingConfiguration;
  @Input() dataSources: string[] = [];

  pairKey = '';
  pairs: { key: string; a: string; b: string }[] = [];
  running = false;
  error: string | null = null;
  result: MatchingPreview | null = null;
  showDiff = false;
  private sub: Subscription | null = null;

  constructor(private settings: MatchingSettingsHttpService) {}

  ngOnChanges(): void {
    const s = [...this.dataSources].sort();
    this.pairs = [];
    for (let i = 0; i < s.length; i++) for (let j = i + 1; j < s.length; j++) this.pairs.push({ key: `${s[i]}⇄${s[j]}`, a: s[i], b: s[j] });
    if (!this.pairs.some(p => p.key === this.pairKey)) this.pairKey = this.pairs[0]?.key ?? '';
  }

  get threshold(): number {
    return this.configuration?.defaults?.threshold ?? 0.75;
  }

  max(r: MatchingPreview): number {
    return Math.max(...r.scoreHistogram);
  }

  sample(s: PreviewSample): string {
    return `${nodeName(s.a)} / ${nodeName(s.b)} (${Math.round(s.score * 100)} %)`;
  }

  run(): void {
    const pair = this.pairs.find(p => p.key === this.pairKey);
    if (!pair) return;
    this.running = true;
    this.error = null;
    this.sub = this.settings.preview(this.configuration, pair.a, pair.b).subscribe({
      next: r => {
        this.result = r;
        this.running = false;
      },
      error: err => {
        this.error = MatchingHttpService.errorMessage(err);
        this.running = false;
      }
    });
  }

  /** Ignores the result; the backend finishes the (read-only) computation anyway. */
  cancel(): void {
    this.sub?.unsubscribe();
    this.running = false;
  }
}
