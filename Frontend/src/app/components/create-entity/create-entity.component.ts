import { Component, OnInit, Inject, Input, Output, EventEmitter, Optional, ChangeDetectorRef, inject } from '@angular/core';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Entity } from '../../models/ressource';

import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import {MatButtonToggleModule} from '@angular/material/button-toggle';
import {MatIconModule} from '@angular/material/icon';

import { debounceTime } from 'rxjs';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { FileViewerComponent } from '../file-viewer/file-viewer.component';
import { MatDialog } from '@angular/material/dialog';

import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';
import {MatChipsModule} from '@angular/material/chips';

export type OntologyLabels = Record<string, string>;

export let ONTOLOGY_LABELS: OntologyLabels = {
  "https://www.ica.org/standards/RiC/ontology#": "RIC-O",
  "http://uspn.fr/app#": "Application",
  "http://www.w3.org/2002/07/owl#": "OWL",
  "http://www.w3.org/2000/01/rdf-schema#": "RDFS",
  "http://purl.org/dc/terms/": "Dublin Core",
  "https://schema.org/": "schema",
  "http://purl.org/dc/elements/1.1/" : "dc"
};

@Component({
  selector: 'app-create-entity',
  imports: [FormsModule,
    ReactiveFormsModule,
    CommonModule, 
    MatSnackBarModule, 
    FileViewerComponent,
    MatButtonToggleModule,
    MatChipsModule,
    MatIconModule
  ],
  
  templateUrl: './create-entity.component.html',
  standalone: true,
  styleUrl: './create-entity.component.scss'
})
export class CreateEntityComponent implements OnInit {

  entityForm: FormGroup;

  selectedEntity: Entity | null = null;

  typeMode: string ="";
  selectedOntology : any = null;

  customSource: 'url' | 'full' = 'url';
  availableTypes: string[] = [];

  listSelectedTypes : any[] = [];

  allPossibleRanges : any[] = [];

  newAssociation: {
    mode: 'existing' | 'new' | null;  
    predicate: any;                 
    ontologyUrl: string;               
    customPredicate: string;          
    valueKind: 'literal' | 'iri';
    value: string;
  } | null = null;

  ontologyList: { name: string; iri: string }[] = [];

  // Used only when opened as a dialog
  public dialogData: any = inject(MAT_DIALOG_DATA, { optional: true });
  private dialogRef: MatDialogRef<CreateEntityComponent> | null = inject(MatDialogRef, { optional: true });

  // Used only when used as a child component tag
  @Input() inputData: any;

  //let the parent know when created inline (no dialogRef to close)
  @Output() closed = new EventEmitter<any>();

  // The single source of truth
  public data: any;

  constructor( 
    private fb: FormBuilder,
    private ontologyService: GestionRessourcesService,
    private snackBar : MatSnackBar,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog
  ) {


    this.entityForm = this.fb.group({
      entityType: ['',Validators.required],
      propertyValue: ['',Validators.required],
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

  extractEntityTypeFromIRI(iri :string ) : string {
    if (iri.includes('#')) return iri.substring(iri.lastIndexOf('#') + 1)
    else if (iri.includes('/')) return iri.substring(iri.lastIndexOf('/') + 1);
    else return iri;
  }

  removeChip(typeName: string) {
    this.listSelectedTypes = this.listSelectedTypes.filter(t => t.label !== typeName);
    this.setSelectedOntology(this.selectedOntology);
  }

  getName(ontology: any): string {
    return ontology?.value?.name;
  }

  checkOneTypePerOntology(ontologyName : string) : boolean {
    return (this.listSelectedTypes.some(item => item.label.startsWith(ontologyName+':')));
  }

  ngOnInit(): void {

    console.log("Ontologies Data recieved  : ", this.dialogData.ontologiesData);
    
    this.entityForm.get('entityType')?.valueChanges
    .subscribe(value => {
      console.log('Selected type:', value);

      if (value && !this.entityForm.get('entityType')?.disabled){
        this.listSelectedTypes.push(
          {
            label: this.typeMode+':'+this.extractEntityTypeFromIRI(value),
            iri :value
          }
        );
        this.entityForm.get('entityType')?.disable();
      }

    });


    if (this.dialogData || this.inputData) {

      this.data = this.inputData ?? this.dialogData;

      console.log("RECEIVED DATA : ", this.data);

      if(this.data?.types){
        this.listSelectedTypes =  this.data?.types
      }
      else if (this.data?.type && this.data?.ontology){
        this.availableTypes = [this.ontologyService.getTypeUrlByName(this.data?.ontology)+this.data.type]
      }
      else this.availableTypes = [];
    }

    if (this.data) {
      this.entityForm.get('entityType')?.patchValue(this.data?.type, { emitEvent: true });
    }

  }


  // ─── Ontology helpers ───────────────────────────────────────────────────────

  getOntologyList(): { name: string; iri: string }[] {
    return Object.entries(ONTOLOGY_LABELS).map(([iri, name]) => ({ name, iri }));
  }

  get resolvedTypeUri(): string {
    const { selectedIri, typeName } = this.entityForm.get('customTypeUrl')?.value ?? {};
    if (!selectedIri || !typeName) return '';
    const sep = selectedIri.endsWith('#') || selectedIri.endsWith('/') ? '' : '#';
    return `${selectedIri}${sep}${typeName}`;
  }

  setSelectedOntology(ontology : any ){
    console.log("Selected ontology" ,ontology);
    this.selectedOntology = ontology;
    if (ontology === null ) {
      this.setTypeMode("custom");
      return ;
    }
    else this.setTypeMode(ontology.name);

    if (this.checkOneTypePerOntology(ontology.name)) {
      this.entityForm?.get('entityType')?.disable();
    }
    else this.entityForm?.get('entityType')?.enable();
  }

  setTypeMode(mode: string) {
    this.typeMode = mode;
    this.entityForm.get('entityType')?.reset();
    this.entityForm.get('customTypeUri')?.reset();
    this.entityForm.get('customTypeUrl')?.reset();
  }

  setCustomSource(src: 'url' | 'full') {
    this.customSource = src;
    this.entityForm.get('customTypeUri')?.reset();
    this.entityForm.get('customTypeUrl')?.reset();
  }

  // ─── Custom fields ───────────────────────────────────────────────────────────

  get customFieldsArray(): FormArray {
    return this.entityForm.get('customFields') as FormArray;
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

  cancelNewEntity() {
    this.entityForm.reset();
    this.dialogRef?.close(); 
  }

  saveNewEntity() {
    let types: string[] = this.listSelectedTypes.map(t => t.iri);

    if (!this.selectedOntology) {
      const customType = this.customSource === 'url'
        ? this.resolvedTypeUri
        : this.entityForm.get('customTypeUri')?.value;

      if (customType) {
        types.push(customType);
      }
    }

    // 2. Build properties (remove "key" and add lang if literal)
    // const formattedProperties = this.properties.map(prop => {
    //   const base: any = {
    //     predicate: prop.predicate,
    //     kind: prop.kind,
    //     value: prop.value
    //   };

    //   // Add lang only for literals
    //   if (prop.kind === 'literal') {
    //     base.lang = ''; // you can make this dynamic later
    //   }

    //   return base;
    // });
    const property: any = {
      predicate: "https://www.ica.org/standards/RiC/ontology#name",
      kind: 'literal',
      value: this.entityForm.get('propertyValue')?.value
    };

    // 3. Final payload
    const payload = {
      types: types,
      properties: [property]
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
        // const dataToSend = { status: 'closed', reason: 'user clicked button' };
        this.closed.emit(true);
        // optional UX improvements
        this.entityForm.reset();
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

  closeWithData() {
    const data = this.listSelectedTypes;
    this.dialogRef?.close(data);
  }

}