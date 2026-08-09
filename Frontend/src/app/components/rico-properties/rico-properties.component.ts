import { Component, Input,Inject, OnInit, OnDestroy,
  ViewChild, ViewContainerRef, ComponentRef } from '@angular/core';
import { CommonModule, NgComponentOutlet  } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MatButtonModule} from '@angular/material/button';
import {MatChipInputEvent, MatChipsModule} from '@angular/material/chips';
import {MatIconModule} from '@angular/material/icon';
import {MatBadgeModule} from '@angular/material/badge';
import { MatRadioModule } from '@angular/material/radio';
import {MatDividerModule} from '@angular/material/divider';

import { Subscription } from 'rxjs';

import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';

@Component({
  selector: 'app-rico-properties',
  imports: [
    CommonModule, 
    FormsModule, 
    ReactiveFormsModule,
    MatCheckboxModule,
    MatButtonModule,
    MatChipsModule,
    MatIconModule,
    MatBadgeModule,
    MatRadioModule,
    MatDividerModule,
    NgComponentOutlet 
  ],
  templateUrl: './rico-properties.component.html',
  styleUrl: './rico-properties.component.scss'
})
export class RicoPropertiesComponent implements OnInit {

  private childClosedSub?: Subscription;
  

  allRanges : any[] = []
  
  allPossibleRanges : any[] = [];

  filters : string[] = [];

  filterByKindValue : string = "";

  predicates: any[] = [];
  predefinedRanges: string[] = [];

  filteredPredicates: any[] = [];

  allRangesLabels : any[] = [];

  rangeIcons: Record<string, string> = {
    Activity: 'task',
    Person: 'person',
    Record: 'description',
    Instantiation: 'category',
    Place: 'place',
  };

  constructor(@Inject(MAT_DIALOG_DATA) public data: any,
    private ontologyService: GestionRessourcesService,
    private dialogRef: MatDialogRef<RicoPropertiesComponent>
  ) {
    this.predicates = data.predicates;
    this.predefinedRanges = data.predefinedRanges;
  }

  ngOnInit() {
    this.filteredPredicates = this.predicates;
    console.log("All predicates : ", this.predicates);
    console.log("All predefined predicates : ", this.predefinedRanges);
    this.allRanges = this.predicates
      .map((predicate: any) => predicate.rangeUri)
      .filter((range: any) => !!range);          // ← drop null/undefined/empty
    this.allRanges = [...new Set(this.allRanges)];
    
    console.log("All possible ranges : ", this.allRanges);

    this.allRanges = this.allRanges.filter((range: string) =>
      !this.predefinedRanges.includes(range)
    );
  }

  onChildClosed(event: any) {
    if (event === true) {
      console.log("Child component closed with success");
    }
    this.destroySubEntity();
  }

  private destroySubEntity() {
    this.childClosedSub?.unsubscribe();
    // this.subEntityRef?.destroy();
    // this.subEntityRef = undefined;
    // this.createSubEntity = false;
  }

  ngOnDestroy() {
    this.destroySubEntity();
  }
  

  removeFilter(filter: string) {
    if (filter === 'iri' || filter === 'literal'){
      const updated = this.filters.filter(
        item => item !== 'iri' && item !== 'literal'
      );
      this.filters = updated;
      this.filteredPredicates = this.predicates;  
    }
    else{
      this.filters = this.filters.filter(f => !f.startsWith('range :'));
    }
  }

  getIconFor(range: string): string {
    return this.rangeIcons[range] ?? 'label';
  }

  getNameOfRicoTypeFromURL(url : string | any) {
    if (url.includes('#')){
      return url.split('#').pop();
    }
    else return url;
  }

  onRangeChange(event : any) {
    const filterUrl = (event.target as HTMLSelectElement)?.value;
    const entityType = this.getNameOfRicoTypeFromURL(filterUrl);
    this.addRangeFilter(entityType);
  }

  filterRangesByFilters() : void {
    for (let filter of this.filters) {
      if (filter.startsWith("range : ")){  
        let rangeName = filter.substring('range : '.length).trim();
        this.filteredPredicates = this.predicates.filter(
          p => this.getNameOfRicoTypeFromURL(p.range) === rangeName
        );
      }
    }
  }

  addRangeFilter(range : any) : void {
    if (this.filters.some(f => f.startsWith('range :'))) {      
      this.filters = this.filters.filter(f => !f.startsWith('range :'));    
    }
    this.filters.push('range : '+range);
    this.filterRangesByFilters();
  }

  filterPropertiesByKindValue(kindValueParam : string) {
    const kindValue = kindValueParam === 'literal' ? 'DATA_PROPERTY' : 'OBJECT_PROPERTY';
    this.filterByKindValue = kindValue;
    const otherKindValue = kindValueParam === 'literal' ? 'iri' : 'literal';
    if (!this.filters.includes(kindValueParam) && this.filters.includes(otherKindValue)) {
      const updated = this.filters.filter(item => item !== otherKindValue);
      this.filters = updated;
      this.filters.push(kindValueParam);
      this.filteredPredicates = this.predicates.filter(predicate => predicate.kind === kindValue);
    }
    if(!this.filters.includes(kindValueParam) && !this.filters.includes(otherKindValue)) {
      this.filters.push(kindValueParam);
      this.filteredPredicates = this.predicates.filter(predicate => predicate.kind === kindValue);
    }
    
  }

  onCheckboxChange(prop: any) {
    console.log("Selected predicate: ", prop);
    this.dialogRef.close(prop);
  }

  confirm() {
    this.dialogRef.close(this.data.association);
  }


}
