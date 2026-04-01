import { Component, OnInit } from '@angular/core';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Entity } from '../../models/ressource';
import { EntityDetailsComponent } from '../entity-details/entity-details.component';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import { debounceTime } from 'rxjs';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';


export type OntologyLabels = Record<string, string>;

export let ONTOLOGY_LABELS: OntologyLabels = {
  "https://www.ica.org/standards/RiC/ontology#": "RIC-O",
  "http://uspn.fr/app#": "Application",
  "http://www.w3.org/2002/07/owl#": "OWL",
  "http://www.w3.org/2000/01/rdf-schema#": "RDFS",
  "http://purl.org/dc/terms/": "Dublin Core"
};

@Component({
  selector: 'app-create-ressource',
  imports: [FormsModule, ReactiveFormsModule, CommonModule, EntityDetailsComponent, MatSnackBarModule],
  templateUrl: './create-ressource.component.html',
  styleUrl: './create-ressource.component.scss'
})
export class CreateRessourceComponent implements OnInit {

  personForm: FormGroup;

  selectedEntity: Entity | null = null;

  properties: { key: string; value: string; kind: 'literal' | 'iri'; predicate: string }[] = [];

  // entityPropertiesDict : any[] = [];


  allPredicatesByType: string[] = [];

  typeMode: 'existing' | 'custom' = 'existing';
  customSource: 'url' | 'full' = 'url';
  availableTypes: string[] = [];

  newAssociation: {
    mode: 'existing' | 'new' | null;  
    predicate: string;                 
    ontologyUrl: string;               
    customPredicate: string;          
    kind: 'literal' | 'iri';
    value: string;
  } | null = null;

  ontologyList: { name: string; iri: string }[] = [];

  constructor(private fb: FormBuilder, private ontologyService: GestionRessourcesService, private snackBar : MatSnackBar) {

    this.personForm = this.fb.group({
      entityType: [''],
      customTypeUri: [''],
      customTypeUrl: this.fb.group({
        selectedIri: [''],
        typeName: ['']
      }),
      associatedWith: this.fb.array([]),
      customFields: this.fb.array([]),
      properties: this.fb.array([])
    });

    this.ontologyList = this.getOntologyList();
  }

  ngOnInit(): void {
    this.ontologyService.getTypes().subscribe({
      next: (data: any[]) => {
        this.availableTypes = data;
      },
      error: (err) => {
        console.error("Erreur lors du chargement des types d'ontology ", err);
      }
    });

    this.personForm.get('entityType')?.valueChanges
    .pipe(debounceTime(300))
    .subscribe(value => {
      if (value) {
        console.log("VALUEEEEE :",value);
        this.ontologyService.getPredicatesByType(value)
          .subscribe(res => {
            console.log(res);
            this.allPredicatesByType = res.map(r => r.p);
          });
      }
    });
  }

  // ─── Ontology helpers ───────────────────────────────────────────────────────

  getOntologyList(): { name: string; iri: string }[] {
    return Object.entries(ONTOLOGY_LABELS).map(([iri, name]) => ({ name, iri }));
  }

  get resolvedTypeUri(): string {
    const { selectedIri, typeName } = this.personForm.get('customTypeUrl')?.value ?? {};
    if (!selectedIri || !typeName) return '';
    const sep = selectedIri.endsWith('#') || selectedIri.endsWith('/') ? '' : '#';
    return `${selectedIri}${sep}${typeName}`;
  }

  setTypeMode(mode: 'existing' | 'custom') {
    this.typeMode = mode;
    this.personForm.get('entityType')?.reset();
    this.personForm.get('customTypeUri')?.reset();
    this.personForm.get('customTypeUrl')?.reset();
  }

  setCustomSource(src: 'url' | 'full') {
    this.customSource = src;
    this.personForm.get('customTypeUri')?.reset();
    this.personForm.get('customTypeUrl')?.reset();
  }

  // ─── Associations ────────────────────────────────────────────────────────────


  // checkAssociationVisibility(index: number): boolean {
  //   const associationGroup = this.associationsArray.at(index);
  //   return associationGroup ? associationGroup.get('show')?.value : false;
  // }
 
  // changeAssociationVisibility(index: number) {
  //   const associationGroup = this.associationsArray.at(index);
  //   if (associationGroup) {
  //     const oldValue = associationGroup.get('show')?.value;
  //     associationGroup.get('show')?.setValue(!oldValue);
  //   }
  // }

  // removeAssociationField(index: number) {
  //   this.associationsArray.removeAt(index);
  // }

  // ─── Custom fields ───────────────────────────────────────────────────────────

  get customFieldsArray(): FormArray {
    return this.personForm.get('customFields') as FormArray;
  }

  addCustomField() {
    const fieldGroup = this.fb.group({
      name: [''],
      value: ['']
    });
    this.customFieldsArray.push(fieldGroup);
  }

  removeCustomField(index: number) {
    this.customFieldsArray.removeAt(index);
  }

  // ─── Form actions ────────────────────────────────────────────────────────────

  addNewPerson() {
    this.personForm.reset();
    while (this.properties.length !== 0) {
      this.properties.pop();
    }
    while (this.customFieldsArray.length !== 0) {
      this.customFieldsArray.removeAt(0);
    }
  }

  cancelNewPerson() {
    this.personForm.reset();
  }

  saveNewPerson() {
    let type: string = '';

    if (this.typeMode === 'existing') {
      type = this.personForm.get('entityType')?.value;
    } 
    else {
      type = this.customSource === 'url'
        ? this.resolvedTypeUri
        : this.personForm.get('customTypeUri')?.value;
    }

    // 2. Build properties (remove "key" and add lang if literal)
    const formattedProperties = this.properties.map(prop => {
      const base: any = {
        predicate: prop.predicate,
        kind: prop.kind,
        value: prop.value
      };

      // Add lang only for literals
      if (prop.kind === 'literal') {
        base.lang = ''; // you can make this dynamic later
      }

      return base;
    });

    // 3. Final payload
    const payload = {
      types: [type],
      properties: formattedProperties
    };

    console.log('Final payload:', payload);

    this.ontologyService.createEntity(payload).subscribe({
      next: (res) => {
        console.log('✅ Entity created:', res);

        this.snackBar.open(
          "✅ Entity created: was successfully created.",
          'Close',
          {
            duration: 5000,
            horizontalPosition: 'center',
            verticalPosition: 'top',
            panelClass: ['snackbar-success']
          }
        );
        // optional UX improvements
        this.personForm.reset();
        this.properties = [];
      },
      error: (err) => {
        this.snackBar.open(
          "❌ Error creating entity.",
          'Close',
          {
            duration: 5000,
            horizontalPosition: 'center',
            verticalPosition: 'top',
            panelClass: ['snackbar-error']
          }
        );      
      }
    });

  
  }

  addAssociation() {
    this.newAssociation = {
      mode: null,
      predicate: '',
      ontologyUrl: '',
      customPredicate: '',
      kind: 'literal',
      value: ''
    };
  }

  confirmAddAssociation() {
    if (!this.newAssociation || !this.newAssociation.value) return;

    let fullPredicate: string;
    let propertyName: string;

    if (this.newAssociation.mode === 'existing') {
      if (!this.newAssociation.predicate) return;
      fullPredicate = this.newAssociation.predicate;
      fullPredicate.includes('#')
        ? propertyName = fullPredicate.substring(fullPredicate.lastIndexOf('#') + 1)
        : propertyName = fullPredicate.substring(fullPredicate.lastIndexOf('/') + 1);
    } else {
      if (!this.newAssociation.ontologyUrl || !this.newAssociation.customPredicate) return;
      const base = this.newAssociation.ontologyUrl; // already ends with # or /
      fullPredicate = base + this.newAssociation.customPredicate;
      propertyName = this.newAssociation.customPredicate;
    }

    this.properties.push({
      key: propertyName,
      value: this.newAssociation.value,
      kind: this.newAssociation.kind,
      predicate: fullPredicate
    });

    this.newAssociation = null;
  }

  cancelAddAssociation() {
    this.newAssociation = null;
  }
}