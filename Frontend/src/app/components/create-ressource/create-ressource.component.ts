import { Component, OnInit, Inject, Input, Output, EventEmitter, Optional, ChangeDetectorRef, inject } from '@angular/core';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Entity } from '../../models/ressource';

import { EntityDetailsComponent } from '../entity-details/entity-details.component';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import { RicoPropertiesComponent } from '../rico-properties/rico-properties.component';

import { debounceTime } from 'rxjs';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { FileViewerComponent } from '../file-viewer/file-viewer.component';
import { MatDialog } from '@angular/material/dialog';

import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';

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
  selector: 'app-create-ressource',
  imports: [FormsModule,
    ReactiveFormsModule,
    CommonModule, 
    EntityDetailsComponent, 
    MatSnackBarModule, 
    FileViewerComponent,
    RicoPropertiesComponent
  ],
  
  templateUrl: './create-ressource.component.html',
  styleUrl: './create-ressource.component.scss'
})
export class CreateRessourceComponent implements OnInit {

  personForm: FormGroup;

  selectedEntity: Entity | null = null;

  properties: { key: string; value: string; kind: 'literal' | 'iri'; predicate: string }[] = [];


  // When Entity is a media file //
  isEntityMediaFile(): boolean {
    return this.newAssociation?.predicate?.p === 'https://www.ica.org/standards/RiC/ontology#identifier' ;
  }

  receiveFilePath(path: string) {
    this.newAssociation!.value = path;
  }

  createSubEntity : boolean = false; 


  allPredicatesByType: any[] = [];

  typeMode: 'defined' |'rico' | 'custom' = 'rico';
  customSource: 'url' | 'full' = 'url';
  availableTypes: string[] = [];

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
  private dialogRef: MatDialogRef<CreateRessourceComponent> | null = inject(MatDialogRef, { optional: true });

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
  onChildClosed(event: any) {
    if (event === true) {
      this.createSubEntity = false;
      this.onPredicateChange(null); // Refresh the list of possible ranges after the child entity is created
    }
  }

  getFullEntityPath(entityType : string, ontologyName: string): string {
    return ontologyName
      ? this.ontologyService.getTypeUrlByName(ontologyName) + entityType
      : '';
  }

  openRicoPropertiesDialog() {
    this.newAssociation!.mode = 'existing';

    const dialogRef = this.dialog.open(RicoPropertiesComponent, {
      width: '600px',
      height: '350px',
      data: {
        predicates: this.allPredicatesByType,
        association: this.newAssociation
      }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        console.log('Dialog result:', result);
        this.confirmAddAssociation();

      } else {
        console.log('Dialog closed without changes');
      }
    });
  }

  extractEntityTypeFromIRI(iri :string ) : string {
    return iri.includes('#') 
      ? iri.substring(iri.lastIndexOf('#') + 1)
      : iri.substring(iri.lastIndexOf('/') + 1);
  }

  ngOnInit(): void {

    if (!this.dialogData && this.inputData === undefined) {
      this.getAllRicoEntities();
    }

    // else if (this.dialogData.type && this.dialogData.ontology || this.inputData.type && this.inputData.ontology ) {
    else if (this.dialogData || this.inputData) {

      this.data = this.inputData ?? this.dialogData;

      console.log("RECEIVED DATA : ", this.data);

      this.availableTypes =  this.data?.type && this.data?.ontology
        ? [this.ontologyService.getTypeUrlByName(this.data?.ontology)+this.data.type]
        : [];
    }

    this.personForm.get('entityType')?.valueChanges
    .pipe(debounceTime(300))
    .subscribe(value => {
      if (value) {

        this.ontologyService.getPredicatesByTypeRico(this.personForm.get('entityType')?.value)
          .subscribe(res => {
            console.log("All predicates : ",res);
            this.allPredicatesByType = Object.values(
              res.reduce((acc, item) => {
                if (!acc[item.p]) acc[item.p] = item;
                return acc;
              }, {})
            );            
            
          }
        );
      }
    });

    if (this.data) {
      // const fullType = this.ontologyService.getTypeUrlByName(this.data.ontology) + this.data.type;
      this.personForm.get('entityType')?.patchValue(this.data?.type, { emitEvent: true });
    }

    // if(this.typeMode === 'rico' && !this.data?.type ) {
    //   this.getAllRicoEntities();
    // }

  }

  createSubEntityToggle() {
    this.createSubEntity = true;
  }

  onPredicateChange(event: any) {
    if (!this.newAssociation) return;


    this.newAssociation.valueKind =
      this.newAssociation.predicate?.valueKind || 'literal';

    // this.ontologyService.getPredicatesByTypeRico(this.data.type)
    // .subscribe(res => {
    //   console.log("All predicates : ",res);
    //   this.allPredicatesByType = Object.values(
    //     res.reduce((acc, item) => {
    //       if (!acc[item.p]) acc[item.p] = item;
    //       return acc;
    //     }, {})
    //   );
    //   //.map(r => r.p);
      
    // });
    

    this.ontologyService.getAllEntitiesByType(this.newAssociation.predicate.range).subscribe({
      next: (res) => {
        this.allPossibleRanges = res;
        console.log("All possible ranges for this predicate : ", res);
      },
      error: (err) => {
        console.error("Error fetching possible ranges: ", err);
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

  setTypeMode(mode: 'rico' | 'custom') {
    this.typeMode = mode;
    this.personForm.get('entityType')?.reset();
    this.personForm.get('customTypeUri')?.reset();
    this.personForm.get('customTypeUrl')?.reset();
  }

  getAllRicoEntities() : void {

    this.ontologyService.getAllRicoClasses().subscribe({
      next: (data) => {
        try {
          // Step 1: Map to type strings
          const allRicoClassesNotFormatted = this.ontologyService.getOntologyLabel(
            data.map((d: any) => d.type)
          );

          // Step 2: Extract the RIC-O array
          let ricOClasses = allRicoClassesNotFormatted[0]['rico'];

          // Step 3: Remove duplicates
          // Assuming each element is a string; if it's an object, use a key like `type`
          ricOClasses = Array.from(new Set(ricOClasses));

          this.availableTypes = ricOClasses;
          console.log("Classes RICO (no duplicates) ", this.availableTypes);

        } catch (e) {
          console.error("ERROR in getOntologyLabel:", e);
        }

        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error(err);
      }
    });
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
    this.dialogRef?.close(); 
  }

  saveNewPerson() {
    let type: string = '';

    if (this.typeMode === 'rico') {
      type = this.getFullEntityPath(this.personForm.get('entityType')?.value, 'rico');
      
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
        // const dataToSend = { status: 'closed', reason: 'user clicked button' };
        this.closed.emit(true);
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
      valueKind: 'literal',
      value: ''
    };
  }

  confirmAddAssociation() {
    if (!this.newAssociation || !this.newAssociation.value) return;

    let fullPredicate: string;
    let propertyName: string;

    if (this.newAssociation.mode === 'existing') {
      if (!this.newAssociation.predicate) return;
      fullPredicate = this.newAssociation.predicate.p;
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
      kind: this.newAssociation.valueKind,
      predicate: fullPredicate
    });

    this.newAssociation = null;
  }

  cancelAddAssociation() {
    this.newAssociation = null;
  }
}