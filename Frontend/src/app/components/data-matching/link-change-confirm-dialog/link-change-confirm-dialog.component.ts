import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { LinkChangeResult, nodeName } from '../../../models/matching.model';

export interface LinkChangeConfirmData {
  title: string;
  confirmLabel: string;
  result: LinkChangeResult;
}

/** Lists the side effects of a link change (computed by a dry run) before applying it. */
@Component({
  selector: 'app-link-change-confirm-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="text-sm space-y-3">
      <div *ngIf="data.result.inferredAdded.length">
        <p class="font-medium text-emerald-700">{{ data.result.inferredAdded.length }} link(s) will be inferred:</p>
        <ul class="list-disc ml-5">
          <li *ngFor="let l of data.result.inferredAdded">
            {{ name(l.a) }} <span class="text-gray-500">({{ l.a.dataSource }})</span> ≡
            {{ name(l.b) }} <span class="text-gray-500">({{ l.b.dataSource }})</span>
          </li>
        </ul>
      </div>
      <div *ngIf="data.result.inferredRemoved.length">
        <p class="font-medium text-amber-700">{{ data.result.inferredRemoved.length }} inferred link(s) will be removed:</p>
        <ul class="list-disc ml-5">
          <li *ngFor="let l of data.result.inferredRemoved">
            {{ name(l.a) }} <span class="text-gray-500">({{ l.a.dataSource }})</span> ≡
            {{ name(l.b) }} <span class="text-gray-500">({{ l.b.dataSource }})</span>
          </li>
        </ul>
      </div>
      <div *ngIf="data.result.warnings.length" class="p-2 rounded bg-amber-50 border border-amber-200 text-amber-800">
        <p *ngFor="let w of data.result.warnings">⚠ {{ w }}</p>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close(false)">Cancel</button>
      <button mat-flat-button color="primary" (click)="ref.close(true)">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `
})
export class LinkChangeConfirmDialogComponent {
  name = nodeName;

  constructor(public ref: MatDialogRef<LinkChangeConfirmDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: LinkChangeConfirmData) {}
}
