import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * "Add property" dialog of the entity editor: the properties the entity's types allow,
 * filtered by the kind of their value (a literal, or an entity of a given type). Closes with
 * the chosen property definition.
 */
@Component({
  selector: 'app-rico-properties',
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatChipsModule,
    MatTooltipModule
  ],
  templateUrl: './rico-properties.component.html',
  styleUrl: './rico-properties.component.scss'
})
export class RicoPropertiesComponent implements OnInit {

  predicates: any[] = [];
  removeProperty: string[] = [];
  predefinedRanges: string[] = [];
  remainingRanges: string[] = [];

  /** All the proposable properties (those listed in `removeProperty` are left out). */
  fixedFilteredPredicates: any[] = [];
  filteredPredicates: any[] = [];
  /** 'literal', a range IRI, or null for no filter. */
  selectedFilter: string | null = null;

  constructor(@Inject(MAT_DIALOG_DATA) public data: any,
              private dialogRef: MatDialogRef<RicoPropertiesComponent>) {
    this.predicates = data.predicates ?? [];
    // configuration.json defines these for some ontologies only
    this.predefinedRanges = data.predefinedRanges ?? [];
    this.removeProperty = data.removeProperty ?? [];
  }

  ngOnInit() {
    this.fixedFilteredPredicates = this.predicates.filter((pred: any) =>
      !this.removeProperty.includes(this.getNameOfRicoTypeFromURL(pred.uri))
    );
    this.filteredPredicates = this.fixedFilteredPredicates;

    const allRanges: string[] = [...new Set<string>(
      this.predicates
        .flatMap((predicate: any) => predicate.ranges ?? [])
        .map((range: any) => range.uri)
        .filter((uri: any) => !!uri)
    )];
    this.predefinedRanges = this.predefinedRanges.filter((range: string) => allRanges.includes(range));
    this.remainingRanges = allRanges.filter((range: string) => !this.predefinedRanges.includes(range));
  }

  getNameOfRicoTypeFromURL(url : string | any) {
    if (url.includes('#')){
      return url.split('#').pop();
    }
    if (url.includes('/')){
      return url.split('/').pop();
    }
    else return url;
  }

  /** One filter at a time, always applied to the full list: no value, 'literal', or a range IRI. */
  onFilterChange(filter: string | null | undefined): void {
    this.selectedFilter = filter || null;
    if (!this.selectedFilter) {
      this.filteredPredicates = this.fixedFilteredPredicates;
    } else if (this.selectedFilter === 'literal') {
      this.filteredPredicates = this.fixedFilteredPredicates.filter(p => p.kind === 'DATA_PROPERTY');
    } else {
      const range = this.selectedFilter;
      this.filteredPredicates = this.fixedFilteredPredicates.filter(
        p => (p.ranges ?? []).some((r: any) => r.uri === range)
      );
    }
  }

  onRangeChange(event: Event) {
    this.onFilterChange((event.target as HTMLSelectElement)?.value);
  }

  onCheckboxChange(prop: any) {
    this.dialogRef.close(prop);
  }
}
