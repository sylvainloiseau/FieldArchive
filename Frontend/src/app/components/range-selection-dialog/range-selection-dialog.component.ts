import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-range-selection-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule
  ],
  template: `
    <div class="p-5">
      
      <h2 class="text-lg font-semibold text-gray-900 mb-1">
        Choose a type
      </h2>

      <p class="text-sm text-gray-500 mb-4">
        Select the type you want to create.
      </p>

      <div class="space-y-2">
        <button
          *ngFor="let range of data.ranges"
          type="button"
          (click)="selectRange(range)"
          class="w-full text-left px-4 py-3 rounded-lg
                 border border-gray-200
                 hover:bg-blue-50 hover:border-blue-400
                 transition-all duration-150">

          <div class="font-medium text-gray-800">
            {{ range.localName }}
          </div>

          <div class="text-xs text-gray-400 mt-1 break-all">
            {{ range.uri }}
          </div>

        </button>
      </div>

      <div class="flex justify-end mt-5">
        <button
          type="button"
          mat-dialog-close
          class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900">
          Cancel
        </button>
      </div>

    </div>
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