import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatChipsModule, MatChipInputEvent } from '@angular/material/chips';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTooltipModule } from '@angular/material/tooltip';
import { COMMA, ENTER } from '@angular/cdk/keycodes';
import { compactIri, expandCurie } from '../../../models/matching.model';

export interface IriOption {
  iri: string;
  curie: string;
  label?: string | null;
  /** Shown on the right of the option, e.g. usage counts. */
  hint?: string;
  /** Greyed in the chips (e.g. a predicate never used in the data). */
  unused?: boolean;
}

/** A list of IRIs as chips, with autocompletion; accepts a typed IRI or CURIE. */
@Component({
  selector: 'app-iri-chip-input',
  standalone: true,
  imports: [CommonModule, FormsModule, MatChipsModule, MatAutocompleteModule, MatFormFieldModule, MatTooltipModule],
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic" class="w-full dense">
      <mat-chip-grid #grid>
        <mat-chip-row *ngFor="let v of values" (removed)="remove(v)" [matTooltip]="v"
                      [ngClass]="isUnused(v) ? 'opacity-50' : ''">
          {{ display(v) }}
          <button matChipRemove aria-label="remove">×</button>
        </mat-chip-row>
        <input [placeholder]="placeholder" [(ngModel)]="text" [matChipInputFor]="grid" [matAutocomplete]="auto"
               [matChipInputSeparatorKeyCodes]="separators" (matChipInputTokenEnd)="addTyped($event)">
      </mat-chip-grid>
      <mat-autocomplete #auto="matAutocomplete" (optionSelected)="addSelected($event)">
        <mat-option *ngFor="let o of filtered" [value]="o.iri">
          <span class="text-sm">{{ o.curie }}</span>
          <span *ngIf="o.label" class="text-xs text-gray-500"> — {{ o.label }}</span>
          <span *ngIf="o.hint" class="text-xs text-gray-400 float-right ml-2">{{ o.hint }}</span>
        </mat-option>
      </mat-autocomplete>
    </mat-form-field>
  `,
  styles: [`:host ::ng-deep .dense .mat-mdc-form-field-infix { padding-top: 6px; padding-bottom: 6px; min-height: 0; }`]
})
export class IriChipInputComponent {
  @Input() values: string[] = [];
  @Input() options: IriOption[] = [];
  @Input() prefixes: Record<string, string> = {};
  @Input() placeholder = 'Add…';
  @Output() valuesChange = new EventEmitter<string[]>();

  text = '';
  readonly separators = [ENTER, COMMA];

  get filtered(): IriOption[] {
    const t = (this.text || '').toLowerCase();
    return this.options
      .filter(o => !this.values.includes(o.iri))
      .filter(o => !t || o.curie.toLowerCase().includes(t) || (o.label ?? '').toLowerCase().includes(t))
      .slice(0, 50);
  }

  display(iri: string): string {
    return compactIri(iri, this.prefixes);
  }

  isUnused(iri: string): boolean {
    return !!this.options.find(o => o.iri === iri)?.unused;
  }

  addTyped(event: MatChipInputEvent): void {
    const v = (event.value || '').trim();
    if (v) this.add(expandCurie(v, this.prefixes));
    event.chipInput.clear();
    this.text = '';
  }

  addSelected(event: MatAutocompleteSelectedEvent): void {
    this.add(event.option.value);
    this.text = '';
  }

  remove(iri: string): void {
    this.valuesChange.emit(this.values.filter(v => v !== iri));
  }

  private add(iri: string): void {
    if (!this.values.includes(iri)) this.valuesChange.emit([...this.values, iri]);
  }
}
