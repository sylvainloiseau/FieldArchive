import { Component, Input,Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MatButtonModule} from '@angular/material/button';
import {MatChipInputEvent, MatChipsModule} from '@angular/material/chips';
import {MatIconModule} from '@angular/material/icon';
import {MatBadgeModule} from '@angular/material/badge';
import { MatRadioModule } from '@angular/material/radio';

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
    MatRadioModule
  ],
  templateUrl: './rico-properties.component.html',
  styleUrl: './rico-properties.component.scss'
})
export class RicoPropertiesComponent implements OnInit {

  constructor(@Inject(MAT_DIALOG_DATA) public data: any,
    private ontologyService: GestionRessourcesService,
    private dialogRef: MatDialogRef<RicoPropertiesComponent>
  ) {
    this.predicates = data.predicates;
    this.association = data.association;
  }

  ngOnInit() {
    console.log("All predicates : ", this.predicates);
    this.filteredPredicates = this.predicates;
    this.allRanges = this.predicates.map((predicate : any) => predicate.range);
    this.allRanges = [...new Set(this.allRanges)];
    // this.allRangesLabels = this.ontologyService.getOntologyLabel_v2(this.allRanges);

    console.log("All possibles ranges for all predicates ", this.allRanges);
  }

  allRanges : any[] = []
  
  allPossibleRanges : any[] = [];

  filters : string[] = [];

  filterByKindValue : string = "";

  predicates: any[] = [];

  filteredPredicates: any[] = [];

  allRangesLabels : any[] = [];

  predefinedRanges = ['Activity', 'Person', 'Record', 'Instantiation', 'Place'];

  rangeIcons: Record<string, string> = {
    Activity: 'task',
    Person: 'person',
    Record: 'description',
    Instantiation: 'category',
    Place: 'place',
  };

  association : any ; 

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

  getNameOfRicoTypeFromURL(url : string) {
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

  filterPropertiesByKindValue(kindValue : string) {
    this.filterByKindValue = kindValue;
    const otherKindValue = kindValue === 'literal' ? 'iri' : 'literal';
    if (!this.filters.includes(kindValue) && this.filters.includes(otherKindValue)) {
      const updated = this.filters.filter(item => item !== otherKindValue);
      this.filters = updated;
      this.filters.push(kindValue);
      this.filteredPredicates = this.predicates.filter(predicate => predicate.valueKind === kindValue);
    }
    if(!this.filters.includes(kindValue) && !this.filters.includes(otherKindValue)) {
      this.filters.push(kindValue);
      this.filteredPredicates = this.predicates.filter(predicate => predicate.valueKind === kindValue);
    }
    
  }

  getEntitiesByType(rangeUrl : string) {
    if (!this.association) return;    

    this.ontologyService.getAllEntitiesByType(rangeUrl).subscribe({
      next: (res) => {
        this.allPossibleRanges = res;
        console.log("All possible ranges for this predicate : ", res);
      },
      error: (err) => {
        console.error("Error fetching possible ranges: ", err);
      } 
    });

  }
  onCheckboxChange(prop: any) {
    // if (prop.selected) {
    this.association.predicate = prop;
    this.association.valueKind = this.association.predicate?.valueKind || 'literal';

    console.log("Selected predicate: ", prop);
    this.getEntitiesByType(prop.range);
      // this.onPredicateChange(null); 
    // }
  }
  cancelAddAssociation() {
    this.association.predicate = null;
  }

  confirm() {
    this.dialogRef.close(this.data.association);
  }


}
