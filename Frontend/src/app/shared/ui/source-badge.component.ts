import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * Where a value or a type comes from: the internal data source (editable) or an external one
 * (read-only, with a lock).
 */
@Component({
  selector: 'app-source-badge',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    <span class="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] leading-none whitespace-nowrap"
          [ngClass]="source === 'internal' ? 'bg-blue-50 text-blue-700' : 'bg-gray-100 text-gray-600'"
          [matTooltip]="tooltip">
      <span *ngIf="source !== 'internal'" aria-hidden="true">🔒</span>{{ name || source || 'external' }}
    </span>
  `
})
export class SourceBadgeComponent {
  /** 'internal' | 'external' */
  @Input() source: string | null | undefined = 'external';
  /** The data source's short name. */
  @Input() name: string | null | undefined = null;

  get tooltip(): string {
    return this.source === 'internal'
      ? 'Internal data source (editable)'
      : `From the external source ${this.name ?? ''} (read-only)`;
  }
}
