import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

/** Asks which type to create when a property accepts several. Closes with the chosen range. */
@Component({
  selector: 'app-range-selection-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule
  ],
  template: `
    <h2 mat-dialog-title>Choose a type</h2>
    <mat-dialog-content class="text-sm">
      <p class="text-gray-600 mb-2">This property accepts several types. Which one do you want to create?</p>
      <div class="border rounded-lg divide-y border-gray-200 divide-gray-200">
        <button
          *ngFor="let range of data.ranges"
          type="button"
          (click)="selectRange(range)"
          class="w-full text-left px-3 py-2 hover:bg-blue-50">
          <div class="font-medium text-gray-900">{{ range.localName }}</div>
          <div class="text-xs text-gray-400 break-all">{{ range.uri }}</div>
        </button>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
    </mat-dialog-actions>
  `
})
export class RangeSelectionDialogComponent {

  constructor(
    private dialogRef: MatDialogRef<RangeSelectionDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { ranges: any[] }
  ) {}

  selectRange(range: any): void {
    this.dialogRef.close(range);
  }
}
