import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

export interface SimpleConfirmData {
  title: string;
  message: string;
  confirmLabel: string;
}

@Component({
  selector: 'app-simple-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="text-sm">{{ data.message }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close(false)">Cancel</button>
      <button mat-flat-button color="primary" (click)="ref.close(true)">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `
})
export class SimpleConfirmDialogComponent {
  constructor(public ref: MatDialogRef<SimpleConfirmDialogComponent, boolean>,
              @Inject(MAT_DIALOG_DATA) public data: SimpleConfirmData) {}
}
