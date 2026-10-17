import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

export interface ConfirmDialogData {
  title: string;
  message?: string;
  /** Label / value lines describing what is confirmed (e.g. Name, IRI). */
  details?: { label: string; value: string | null | undefined; mono?: boolean }[];
  confirmLabel: string;
  /** A destructive action: the confirm button is red. */
  warn?: boolean;
}

/** A confirmation dialog; closes with true when confirmed. */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="text-sm space-y-3">
      <p *ngIf="data.message" class="text-gray-600">{{ data.message }}</p>
      <div *ngFor="let d of data.details ?? []">
        <div class="section-label">{{ d.label }}</div>
        <div class="break-all" [ngClass]="d.mono ? 'text-xs text-gray-500 font-mono' : 'text-gray-900'">{{ d.value || '—' }}</div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close(false)">Cancel</button>
      <button mat-flat-button [color]="data.warn ? 'warn' : 'primary'" (click)="ref.close(true)">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `
})
export class ConfirmDialogComponent {
  constructor(public ref: MatDialogRef<ConfirmDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: ConfirmDialogData) {}
}
