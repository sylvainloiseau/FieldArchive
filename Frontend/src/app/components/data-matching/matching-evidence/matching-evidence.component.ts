import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ConceptEvidence } from '../../../models/matching.model';

/** Why a pair got its score: per concept, the two best values, their score and the weight. */
@Component({
  selector: 'app-matching-evidence',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    <table class="w-full text-xs" *ngIf="evidence?.length; else none">
      <thead>
        <tr class="text-gray-500 text-left">
          <th class="py-1 pr-2 font-medium">Concept</th>
          <th class="py-1 pr-2 font-medium">{{ labelA }}</th>
          <th class="py-1 pr-2 font-medium">{{ labelB }}</th>
          <th class="py-1 pr-2 font-medium w-28">Score</th>
          <th class="py-1 font-medium text-right" matTooltip="Weight of the concept in the mean">Weight</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let e of evidence" class="border-t border-gray-100">
          <td class="py-1 pr-2 font-medium text-gray-700" [matTooltip]="e.comparator + ' comparator'">{{ e.concept }}</td>
          <td class="py-1 pr-2 break-all">{{ e.valueA }}</td>
          <td class="py-1 pr-2 break-all">{{ e.valueB }}</td>
          <td class="py-1 pr-2">
            <div class="flex items-center gap-1">
              <div class="h-1.5 w-14 bg-gray-200 rounded">
                <div class="h-1.5 rounded" [ngClass]="barClass(e.score)" [style.width.%]="e.score * 100"></div>
              </div>
              <span [ngClass]="textClass(e.score)">{{ e.score * 100 | number:'1.0-0' }} %</span>
            </div>
          </td>
          <td class="py-1 text-right text-gray-500">×{{ e.weight }}</td>
        </tr>
      </tbody>
    </table>
    <ng-template #none><p class="text-xs text-gray-500 italic">No concept could be compared.</p></ng-template>
  `
})
export class MatchingEvidenceComponent {
  @Input() evidence: ConceptEvidence[] | null = [];
  @Input() labelA = 'A';
  @Input() labelB = 'B';

  barClass(score: number): string {
    return score >= 0.9 ? 'bg-emerald-500' : score >= 0.6 ? 'bg-amber-400' : 'bg-red-400';
  }

  textClass(score: number): string {
    return score >= 0.9 ? 'text-emerald-700' : score >= 0.6 ? 'text-amber-700' : 'text-red-600';
  }
}
