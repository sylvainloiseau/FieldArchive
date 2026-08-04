import { Component, OnInit, Input, OnChanges, SimpleChanges, Inject, inject, ChangeDetectorRef, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Entity } from '../../models/ressource';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import {MatTabsModule} from '@angular/material/tabs';

import {GestionRessourcesService} from '../../services/gestion-ressources.service';
import { MatDialogModule, MatDialogRef, MatDialog, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { Stack } from '../../shared/utils/stack';
import { ConfirmDeleteDialogComponent, ConfirmDeleteDialogData } from '../confirm-delete-dialog/confirm-delete-dialog.component';
import { ConfirmDeletePropertyData, ConfirmDeletePropertyComponent } from '../confirm-delete-property/confirm-delete-property.component';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {ONTOLOGY_LABELS} from '../../models/ontology-labels';

import { FileViewerComponent } from '../file-viewer/file-viewer.component';
import { CreateEntityComponent } from '../create-entity/create-entity.component';

import {MatChipsModule} from '@angular/material/chips';
import {MatIconModule} from '@angular/material/icon';
import { KeyValue } from '@angular/common';


@Component({
  selector: 'app-entity-details',
  standalone: true,
  imports: [
    CommonModule, 
    FormsModule, 
    MatTabsModule,
    ReactiveFormsModule, 
    MatDialogModule, 
    MatSnackBarModule, 
    FileViewerComponent,
    CreateEntityComponent,
    MatChipsModule,
    MatIconModule
  ],
  templateUrl: './entity-details.component.html',
  styleUrl: './entity-details.component.scss'
})
export class EntityDetailsComponent implements OnInit {

  selectedEntityId: string = "";  
  ontologyLabels: Record<string, any>[] = [];
  @Output() close = new EventEmitter<void>();

  stack = new Stack<any>();

  selectedEntity : any = null ;
  entityPropertiesDict : any[] = [];
  newAssociation: {
  mode: 'existing' | 'new' | null;  // which sub-mode
  predicate: string;                 // for 'existing': full predicate URI
  ontologyUrl: string;               // for 'new': selected ontology base URL
  customPredicate: string;           // for 'new': user-typed predicate name
  kind: 'literal' | 'iri';
  value: string;
  } | null = null;

  allEntityTypesChips : any[] = [];

  private gestionRessourceService = inject(GestionRessourcesService);
  private cdr = inject(ChangeDetectorRef); 
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  public listOfAllRanges : any[] = [];

  private lastLoadedRangeUri: string = '';


  constructor(
    @Inject(MAT_DIALOG_DATA) public data: any,
    private dialogRef: MatDialogRef<EntityDetailsComponent>
  ) {
    this.ontologyLabels = data.ontologyLabels;
    this.selectedEntityId = data.selectedEntityId;
  }

  copyToClipboard(text: string | undefined) {
    if (text && navigator.clipboard) {
      navigator.clipboard.writeText(text).then(() => {
        console.log('Copied to clipboard:', text);
        // TODO: Show toast notification
      }).catch(err => {
        console.error('Failed to copy:', err);
      });
    }
  }

  openCreateEntityDialog() {  

    const types = this.allEntityTypesChips;

    const dialogRef = this.dialog.open(CreateEntityComponent, {
      width: '600px',
      data: {
        types,
        ontologiesData: this.ontologyLabels
      }
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        console.log('Received from child:', result);
        this.allEntityTypesChips = result;
        this.editEntity();
      }
    });
  }

  // onRangeSelected(selectedIri: string, propertyValues: any[]): void {
  //   if (!selectedIri) return;

    
  //   const selectedRange = this.listOfAllRanges.find(t => t.iri === selectedIri);
  //   console.log("Values so far for this property:", propertyValues);

  //   propertyValues.push(selectedRange)
  // }
  trackByIri(index: number, item: { iri: string; label: string }): string {
    return item.iri;
  }

  onRangeSelected(event: Event, property: any): void {
    const selectedIri = (event.target as HTMLSelectElement).value;
    if (!selectedIri) return;

    const selectedRange = this.listOfAllRanges.find(t => t.iri === selectedIri);
    if (!selectedRange) return;

    // Push a proper RdfValueDto-shaped object into this property's values,
    // not the raw entity, and NOT into selectedEntity.properties.
    property.values = property.values || [];
    property.values.push({
      value: selectedRange.iri,
      name: selectedRange.label,
      datatype: null,
      lang: null
    });

    this.editEntity();

    (event.target as HTMLSelectElement).value = '';
  }

  openCreateEntityDialogForRange(rangeTypeIRI : string) {

    this.dialog.open(CreateEntityComponent, {
      width: '600px',
      data: {
        fullType : rangeTypeIRI,
        ontologiesData: this.ontologyLabels
      }
    });
  }

  changeSelectedEntity(entityIri : string) {
    if (entityIri) {
      this.stack.push(this.selectedEntity);
      this.gestionRessourceService.getEntityDetails(entityIri).subscribe({
        next: (data) => {
          console.log("DATA:", data);
          this.selectedEntity = data;
          // this.getEntityPropertiesDict();
          this.buildEntityDetails(this.selectedEntity.properties, this.ontologyLabels);
          this.buildTypesChips(data.types, this.ontologyLabels);
          this.buildMainProperties(this.ontologyLabels);
          this.cdr.markForCheck();
        },
        error: (err) => console.error("ERROR:", err)
      });
    }

  }

  extractPropertyNameFromIRI(iri :string ) : string {
    if (iri.includes('#')) return iri.substring(iri.lastIndexOf('#') + 1)
    else if (iri.includes('/')) return iri.substring(iri.lastIndexOf('/') + 1);
    else return iri;
  }

  // Chips 

  buildTypesChips(entityTypes : string[], ontologies : any[]) : void {

    this.allEntityTypesChips = []; // Clear existing chips

    for (let type of entityTypes) {
      for (const [key, value] of Object.entries(ontologies)) {
        if (type.startsWith(key)) {
          this.allEntityTypesChips.push(
            {
              "label" : value.name+":"+this.extractPropertyNameFromIRI(type),
              "iri" : type
            }

          );
        }
      }
    }

  }

  removeChip(typeUrl: string) {
    this.allEntityTypesChips = this.allEntityTypesChips.filter(t => t.iri !== typeUrl);
    this.editEntity();
  }

  buildMainProperties(ontologyLabels: any[]): void {
    const labelSet = new Set(this.allEntityTypesChips.map(t => t.label));

    for (const value of Object.values(ontologyLabels)) {
      const ontologyPrefix = value.name;

      if (value.mainProperty) {
        value.mainProperties = {}; // 👈 object now, same shape as mainProperty

        for (const [keyMP, valueMP] of Object.entries(value.mainProperty)) {
          const entityType = this.extractPropertyNameFromIRI(keyMP);
          const fullKey = `${ontologyPrefix}:${entityType}`;

          if (labelSet.has(fullKey)) {

            if (value.mainProperties[entityType]) {
              // append to existing array for that type
              value.mainProperties[entityType].push(
                ...(Array.isArray(valueMP) ? valueMP : [valueMP])
              );
            } else {
              // create the key, same as mainProperty[entityType]
              value.mainProperties[entityType] = Array.isArray(valueMP)
                ? [...valueMP]
                : [valueMP];
            }
          }
        }
      }
    }

    console.log("WATATATATA :", ontologyLabels);
  }

  checkIfEntityPropertyIsInMainProperties(selectedEntity: any, ontologyLabels: any) {

    for (const [namespaceUri, ontologyData] of Object.entries<any>(ontologyLabels)) {

      const mainPropertyMap = ontologyData?.mainProperties;
      const propertyDefs: any[] = ontologyData?.properties?.value || [];
      const entities: any[] = ontologyData?.entities || [];

      if (!mainPropertyMap || typeof mainPropertyMap !== 'object') {
        continue;
      }

      for (const [typeName, propList] of Object.entries<any>(mainPropertyMap)) {

        if (!Array.isArray(propList)) {
          continue;
        }

        mainPropertyMap[typeName] = propList.map((propIdentifier: any) => {

          if (typeof propIdentifier === 'object' && propIdentifier !== null) {
            return propIdentifier;
          }

          const propDef = propertyDefs.find((p: any) =>
            p.uri === propIdentifier ||
            this.extractPropertyNameFromIRI(p.uri) === propIdentifier
          ) || {};

          let matchedEntity: any = null;
          let matchedIndex = -1;

          for (let i = 0; i < entities.length; i++) {
            const entity = entities[i];
            const localName = this.extractPropertyNameFromIRI(entity.predicate);
            if (entity.predicate === propIdentifier || localName === propIdentifier) {
              matchedEntity = entity;
              matchedIndex = i;
              break;
            }
          }

          if (matchedIndex !== -1) {
            entities.splice(matchedIndex, 1);
          }

          if (matchedEntity) {
            matchedEntity.schema = {
              uri: propIdentifier,
              label: propDef.label,
              cardinality: propDef.cardinality,
              rangeLocalName: propDef.rangeLocalName,
              rangeUri: propDef.rangeUri,
              domainUri: propDef.domainUri,
              domainLocalName: propDef.domainLocalName,
              datatypeCategory: propDef.datatypeCategory,
              datatypeUri: propDef.datatypeUri,
              lang: propDef.lang,
            };
            return matchedEntity;
          }

          // No existing value yet — build a placeholder, give it a real
          // predicate, and register it in selectedEntity.properties so
          // it's tracked from the start (not orphaned once a value is added).
          const predicateUri = propDef.uri || propIdentifier;

          const placeholder: any = {
            predicate: predicateUri,
            kind: propDef.kind === 'OBJECT_PROPERTY' ? 'iri' : 'literal',
            values: [],
            schema: {
              uri: predicateUri,
              label: propDef.label,
              cardinality: propDef.cardinality,
              rangeLocalName: propDef.rangeLocalName,
              rangeUri: propDef.rangeUri,
              domainUri: propDef.domainUri,
              domainLocalName: propDef.domainLocalName,
              datatypeCategory: propDef.datatypeCategory,
              datatypeUri: propDef.datatypeUri,
              lang: propDef.lang,
            },
          };

          selectedEntity.properties.push(placeholder);

          return placeholder;
        });
      }
    }
  }

  cleanProperties(ontologyLabels: any, selectedEntity: any) {
    for (const property of selectedEntity.properties) {
      for (const value of Object.values(ontologyLabels) as any[]) {
        for (const propvalue of value.properties.value) {
          if (property.predicate === propvalue.uri) {
            property.label = propvalue.label;
            property.cardinality = propvalue.cardinality;
            property.rangeLocalName = propvalue.rangeLocalName;
            property.rangeUri = propvalue.rangeUri;
            property.domainUri = propvalue.domainUri;
            property.domainLocalName = propvalue.domainLocalName;
            property.datatypeCategory = propvalue.datatypeCategory;
            property.datatypeUri = propvalue.datatypeUri;
            property.lang = propvalue.lang;
          }
        }
      }
    }

    // Enrich mainProperties once
    this.checkIfEntityPropertyIsInMainProperties(selectedEntity, ontologyLabels);

    console.log("NEW CLEANED SELECTED OBJECT :", selectedEntity);
    console.log("NEW CLEANED ONTOLOGY OBJECT :", ontologyLabels);
  }

  buildEntityDetails(properties : any[], ontologyLabels : any[]) : void {

    for (const [key, value] of Object.entries(ontologyLabels)) {
      value.entities = [];
    }


    for (const property of properties) {

      for(const [key, value] of Object.entries(ontologyLabels)){
        if (property.predicate.startsWith(key)){
          property.key = this.extractPropertyNameFromIRI(property.predicate);
          value.entities.push(property);
        }

      }
    }

    console.log("ONTOLOGY LABELS NEW :" ,ontologyLabels);
  }

  ontologyOrderComparator = (
    a: KeyValue<string, any>,
    b: KeyValue<string, any>
  ): number => {
    const priorityKey = 'https://www.ica.org/standards/RiC/ontology';

    if (a.key === priorityKey) return -1;
    if (b.key === priorityKey) return 1;

    return a.value.name.localeCompare(b.value.name);
  };


  ngOnInit(): void {
    this.ontologyLabels = this.data.ontologyLabels;
    this.selectedEntityId = this.data.selectedEntityId;

    console.log("Dialog data:", this.data);
    if (this.selectedEntityId) {
      this.gestionRessourceService
        .getEntityDetails(this.selectedEntityId)
        .subscribe({
          next: (data) => {
            console.log("Entity Details Data:", data);
            this.buildEntityDetails(data.properties, this.ontologyLabels);
            this.selectedEntity = data;
            this.buildTypesChips(data.types, this.ontologyLabels);
            this.buildMainProperties(this.ontologyLabels);

            this.cleanProperties(this.ontologyLabels, this.selectedEntity);

            this.cdr.markForCheck();
          },
          error: (err) => console.error("ERROR:", err)
        });
    }

  }


  // Remove association
  removeAssociation(person: string) {
    if (this.selectedEntity && this.selectedEntity.associatedWith) {
      this.selectedEntity.associatedWith = this.selectedEntity.associatedWith.filter((p : any) => p !== person);
    }
  }


  backToPreviousEntity() {
    if (!this.stack.isEmpty()) {
      const prev = this.stack.pop();
      this.selectEntity(prev);
    }
  }

  selectEntity(entity: any) {
    this.selectedEntity = entity;
    // this.getEntityPropertiesDict(); // ← ajouter
    this.buildEntityDetails(this.selectedEntity.properties, this.ontologyLabels);
    this.buildTypesChips(this.selectedEntity.types, this.ontologyLabels);

    this.buildMainProperties(this.ontologyLabels);
    this.cdr.markForCheck();
  }

  closeDetail() {
    this.dialogRef.close();
    // this.close.emit();
  }

  deleteEntity() {
    if (!this.selectedEntity) return;

    const dialogRef = this.dialog.open(ConfirmDeleteDialogComponent, {
      data: {
        entityLabel: this.selectedEntity.titre,
        entityIri: this.selectedEntity.iri,
      } as ConfirmDeleteDialogData,
      panelClass: 'rounded-xl',
    });

    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {

        this.gestionRessourceService.deleteEntity(this.selectedEntity.iri).subscribe({
          next: () => {
            this.snackBar.open(
              `Entity was successfully deleted.`,
              'Close',
              {
                duration: 5000,
                horizontalPosition: 'center',
                verticalPosition: 'top',
                panelClass: ['snackbar-success']
              }
            );
            console.log('Entity deleted:', this.selectedEntity.iri);
            this.closeDetail();
          },
          error: (err : any) => {
            console.error('Delete failed:', err)
             this.snackBar.open(
              `Failed to delete "${this.selectedEntity.iri}".`,
              'Close',
              {
                duration: 5000,
                horizontalPosition: 'right',
                verticalPosition: 'bottom',
                panelClass: ['snackbar-error']
              }
            );
          }
        });
      }
    });
  }

  editEntity(): void {
    const properties = (this.selectedEntity.properties || [])
      .filter((p: any) => p && p.predicate && p.kind) // drop anything malformed/corrupted
      .map((p: any) => ({
        predicate: p.predicate,
        kind: p.kind,
        values: (p.values || [])
          .filter((v: any) => v && v.value != null)
          .map((v: any) => ({
            value: v.value,
            datatype: v.datatype ?? null,
            lang: v.lang ?? null
          }))
      }))
      .filter((p: any) => p.values.length > 0); // drop properties left with no values

    const payload = {
      types: this.allEntityTypesChips.map(type => type.iri),
      properties
    };

    console.log("ENTITY TO BE UPDATED : ", payload);

    this.gestionRessourceService.editEntity(this.selectedEntity.iri, payload).subscribe({
      next: () => {
        this.snackBar.open(
          `"${this.selectedEntity.iri}" was successfully updated.`,
          'Close',
          { duration: 5000, horizontalPosition: 'center', verticalPosition: 'top', panelClass: ['snackbar-success'] }
        );
      },
      error: (err) => {
        console.error('Edit failed:', err);
        this.snackBar.open(
          `Failed to update "${this.selectedEntity.iri}".`,
          'Close',
          { duration: 5000, horizontalPosition: 'center', verticalPosition: 'top', panelClass: ['snackbar-error'] }
        );
      }
    });
  }

  removeProperty(property: any) {
    if (this.selectedEntity && this.selectedEntity.properties) {
    
    const dialogRef = this.dialog.open(ConfirmDeletePropertyComponent, {
      data: {
        propertyLabel: property.key,
        entityIri: this.selectedEntity.iri,
        value : property.value
      } as ConfirmDeletePropertyData,
      panelClass: 'rounded-xl',
    });

    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        const prop = this.entityPropertiesDict.find(
          (p: any) => p.predicate === property.predicate
        );
        if (prop) {
          prop.value = "";
        }
        console.log("query payload after removing property : ", this.entityPropertiesDict);
        this.editEntity(); 
        this.entityPropertiesDict = this.entityPropertiesDict.filter((p : any) => p.predicate !== property.predicate && p.value !== property.value); 
        this.cdr.markForCheck();

      }
    });

    }
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

  getEntitiesByType(rangeUri: string): void {
    this.listOfAllRanges = []; 
    if (this.lastLoadedRangeUri === rangeUri && this.listOfAllRanges?.length) {
      return; // already loaded, don't refetch/reassign
    }
    this.lastLoadedRangeUri = rangeUri;
    this.gestionRessourceService.getAllEntitiesByType(rangeUri).subscribe({
      next: (res) => {
        this.listOfAllRanges = res;
      },
      error: (err) => {
        console.error("Error fetching possible ranges: ", err);
      } 
    });
  }

  confirmAddAssociation() {
    if (!this.newAssociation || !this.newAssociation.value) return;

    let fullPredicate: string;
    let propertyName: string;
    let ontologyUrl: string;

    if (this.newAssociation.mode === 'existing') {
      if (!this.newAssociation.predicate) return;
      fullPredicate = this.newAssociation.predicate;

      if (fullPredicate.includes('#')) {
        ontologyUrl = fullPredicate.substring(0, fullPredicate.lastIndexOf('#') + 1);
        propertyName = fullPredicate.substring(fullPredicate.lastIndexOf('#') + 1);
      } else {
        ontologyUrl = fullPredicate.substring(0, fullPredicate.lastIndexOf('/') + 1);
        propertyName = fullPredicate.substring(fullPredicate.lastIndexOf('/') + 1);
      }
    } else {
      if (!this.newAssociation.ontologyUrl || !this.newAssociation.customPredicate) return;
      ontologyUrl = this.newAssociation.ontologyUrl; // already ends with # or /
      fullPredicate = ontologyUrl + this.newAssociation.customPredicate;
      propertyName = this.newAssociation.customPredicate;
    }

    const ontologyName = this.gestionRessourceService.getTypeNameByUrl(ontologyUrl);

    this.entityPropertiesDict.push({
      key: propertyName,
      value: this.newAssociation.value,
      kind: this.newAssociation.kind,
      predicate: fullPredicate,
      ontology: ontologyName   // matches detailTab
    });

    this.newAssociation = null;
    this.cdr.markForCheck(); 
  }

  cancelAddAssociation() {
    this.newAssociation = null;
  }

  isFilePath(path: string): boolean {

    if (!path) return false;

    const windowsPath = /^[a-zA-Z]:\\.*$/;              // C:\...
    const windowsUNC = /^\\\\[^\\]+\\[^\\]+/;           // \\Server\Share
    const unixPath = /^\/(Users|home|tmp|var|etc|opt)/; // /Users/... or /home/...
    const fileUrl = /^file:\/\/\/.+/;                   // file:///...

    return (
      windowsPath.test(path) ||
      windowsUNC.test(path) ||
      unixPath.test(path) ||
      fileUrl.test(path)
    );
  }

  openFileViewer(property: any, filePath: string) {
    if (!filePath ) return ;

    console.log("Opening file viewer for property: ", property);

    const dialogRef = this.dialog.open(FileViewerComponent, {
      width: '1000px',
      data: filePath
    });

    dialogRef.afterClosed().subscribe((newFilePath) => {
      if (newFilePath) {
        console.log('Received from File Viewer dialog:', newFilePath);
        property.value = newFilePath; // Update the property value with the new file path
        this.cdr.markForCheck();

      } else {
        console.log('File ViewerDialog closed without selection');
      }
    });
  }

}
