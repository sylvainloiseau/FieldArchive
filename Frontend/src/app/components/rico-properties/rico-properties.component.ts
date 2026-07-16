import { Component, Input,Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MatButtonModule} from '@angular/material/button';
import {MatChipInputEvent, MatChipsModule} from '@angular/material/chips';
import {MatIconModule} from '@angular/material/icon';
import {MatBadgeModule} from '@angular/material/badge';

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
    MatBadgeModule
  ],
  templateUrl: './rico-properties.component.html',
  styleUrl: './rico-properties.component.scss'
})
export class RicoPropertiesComponent implements OnInit {

  constructor(@Inject(MAT_DIALOG_DATA) public data: any,private ontologyService: GestionRessourcesService) {
    this.predicates = data.predicates;
    this.association = data.association;
  }

  ngOnInit() {
    console.log("All predicated : ", this.predicates);
    this.filteredPredicates = this.predicates;
  }
  
  allPossibleRanges : any[] = [];

  filters : string[] = [];

  filterByKindValue : string = "";

  predicates: any[] = [];

  filteredPredicates: any[] = [];

  association : any ; 

  removeKindValueFilter() {
      const updated = this.filters.filter(
        item => item !== 'iri' && item !== 'literal'
      );
      this.filters = updated;
      this.filteredPredicates = this.predicates;
    
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

  onPredicateChange(event: any) {
    if (!this.association) return;


    this.association.valueKind = this.association.predicate?.valueKind || 'literal';
    

    this.ontologyService.getAllEntitiesByType(this.association.predicate.range).subscribe({
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
      console.log("Selected predicate: ", prop);
      this.onPredicateChange(null); 
    // }
  }


}
