import { Component, OnInit, Input, OnChanges, SimpleChanges, Inject, inject, ChangeDetectorRef, Output, EventEmitter } from '@angular/core';
import { CommonModule, KeyValue } from '@angular/common';
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
import {RicoPropertiesComponent} from '../rico-properties/rico-properties.component';
import { RangeSelectionDialogComponent } from '../range-selection-dialog/range-selection-dialog.component';

import {MatChipsModule} from '@angular/material/chips';
import {MatIconModule} from '@angular/material/icon';
import { forkJoin } from 'rxjs';

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
    RicoPropertiesComponent,
    RangeSelectionDialogComponent,
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

  ontologyEntries: { key: string; value: any }[] = [];
  selectedOntologyTab : {key : string , value : any} = {key : "" , value : null} ;


  constructor(
    @Inject(MAT_DIALOG_DATA) public data: any,
    private dialogRef: MatDialogRef<EntityDetailsComponent>
  ) {
    this.ontologyLabels = data.ontologyLabels;
    this.selectedEntityId = data.selectedEntityId;
  }

  MainPropertiesisNotEmpty(obj: any): boolean {
    // for (const entityType of this.allEntityTypesChips) {
    //   if (entityType.startsWith(obj.name + ':')) {
    //     console.log("GOT IT :", entityType);
    //     const properties = obj.main_Properties[entityType.substring((obj.name + ':').length)];

    //     return Array.isArray(properties) && properties.length > 0;
    //   }
    // }
    return obj && Object.keys(obj).length > 0;
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
        ontologiesData: this.ontologyLabels,
        update : true
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


  trackByIri(index: number, item: { iri: string; label: string }): string {
    return item.iri;
  }

  addNewDataTypeAuthorized(property : any) : boolean {
    for (const value of property.values) {
      if (!value.value || value.value.trim() === '') {
        return false; 
      }
    }
    return true;
  }

  addNewDataType(property: any) {
    property.values.push({kind : 'literal', lang : null, value : '', source : 'internal', editing: true, datatype : "http://www.w3.org/2001/XMLSchema#string"});
    console.log("Add new data type : ", property);
  }

  onRangeSelected(event: Event, property: any): void {
    const selectedIri = (event.target as HTMLSelectElement).value;
    if (!selectedIri) return;

    const selectedRange = this.listOfAllRanges.find(t => t.iri === selectedIri);
    if (!selectedRange) return;

    const newValue = {
      value: selectedRange.iri,
      name: selectedRange.label,
      datatype: null,
      lang: null
    };

    // 🔍 Cherche si la propriété existe déjà dans selectedEntity
    const existingProperty = this.selectedEntity.properties.find(
      (p: any) => p.predicate === property.predicate
    );

    if (existingProperty) {
      // ✅ éviter doublon de valeur
      const alreadyExists = existingProperty.values.some(
        (v: any) => v.value === newValue.value
      );

      if (!alreadyExists) {
        existingProperty.values.push(newValue);
      }
    } else {
      // ✅ créer UNE SEULE propriété
      this.selectedEntity.properties.push({
        predicate: property.predicate,
        kind: property.kind,
        values: [newValue]
      });
    }

    this.editEntity();

    (event.target as HTMLSelectElement).value = '';
  }

  onRangeDeleted(range: any, property: any): void {

    const rangeValue = range?.value ;
    console.log("Selected IRI for deletion:", rangeValue);
    console.log("Property object:", property);
    console.log("Property values:", property.values);

    if ( (rangeValue.trim() === '' || range.editing) && property.kind === 'literal') {
      property.values = property.values.filter((v: any) => v.value !== rangeValue);
      return
    }

    const dialogRef = this.dialog.open(ConfirmDeletePropertyComponent, {
      data: {
        propertyLabel: property.key,
        entityIri: this.selectedEntity.iri,
        value : rangeValue
      } as ConfirmDeletePropertyData,
      panelClass: 'rounded-xl',
    });

    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        if (!rangeValue) return;

        property.values = property.values.filter((v: any) => v.value !== rangeValue);

        console.log("Values after deletion for this rangeIri:", rangeValue, property.values);

        this.editEntity();

        // this.entityPropertiesDict = this.entityPropertiesDict.filter((p : any) => p.predicate !== property.predicate && p.value !== property.value); 
        // this.cdr.markForCheck();

      }
    });
  
    
  }

  openCreateEntityDialogForRange(property: any): void {
  const rangeUris: string[] = Array.isArray(property.rangeUri)
    ? property.rangeUri
    : [property.rangeUri];

  const rangeLocalNames: string[] = Array.isArray(property.rangeLocalName)
    ? property.rangeLocalName
    : [property.rangeLocalName];

  if (!rangeUris.length || !rangeUris[0]) return;

  // Multiple possible types -> let the user pick one first
  if (rangeUris.length > 1) {
    const ranges = rangeUris.map((uri, i) => ({
      uri,
      localName: rangeLocalNames[i] || this.extractPropertyNameFromIRI(uri)
    }));

    const selectionDialogRef = this.dialog.open(RangeSelectionDialogComponent, {
      width: '450px',
      data: { ranges }
    });

    selectionDialogRef.afterClosed().subscribe((selectedRange: any) => {
      if (selectedRange?.uri) {
        this.openCreateEntityDialogWithType(selectedRange.uri);
      }
    });
    return;
  }

  // Single possible type -> go straight to entity creation
  this.openCreateEntityDialogWithType(rangeUris[0]);
}

private openCreateEntityDialogWithType(rangeTypeIRI: string): void {
  this.dialog.open(CreateEntityComponent, {
    width: '600px',
    data: {
      fullType: rangeTypeIRI,
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
  buildTypesChips(entityTypes: any[], ontologies: any[]): void {

    this.allEntityTypesChips = []; // Clear existing chips

    for (let type of entityTypes) {
      const typeIri = type.iri; // was: type (plain string) before

      for (const [key, value] of Object.entries(ontologies)) {
        if (typeIri.startsWith(key)) {
          this.allEntityTypesChips.push(
            {
              "label": value.name + ":" + this.extractPropertyNameFromIRI(typeIri),
              "iri": typeIri,
              "source": type.source                  
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

      if (value.mainProperties) {
        value.main_Properties = {}; // 👈 object now, same shape as mainProperty

        for (const [keyMP, valueMP] of Object.entries(value.mainProperties)) {
          const entityType = this.extractPropertyNameFromIRI(keyMP);
          const fullKey = `${ontologyPrefix}:${entityType}`;

          if (labelSet.has(fullKey)) {

            console.log("LABEL SET : ", fullKey);

            if (value.main_Properties[entityType]) {
              // append to existing array for that type
              value.main_Properties[entityType].push(
                ...(Array.isArray(valueMP) ? valueMP : [valueMP])
              );
            } else {
              // create the key, same as mainProperties[entityType]
              value.main_Properties[entityType] = Array.isArray(valueMP)
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

          // Always reduce to a plain string key — a predicate URI or local
          // name — whether the config gave us a string or a pre-built
          // object ({ predicate, kind, schema, ... }). Never skip matching
          // just because the entry happens to already be an object.
          const key = (typeof propIdentifier === 'object' && propIdentifier !== null)
            ? (propIdentifier.predicate || propIdentifier.uri)
            : propIdentifier;

          const propDef = propertyDefs.find((p: any) =>
            p.uri === key ||
            this.extractPropertyNameFromIRI(p.uri) === key
          ) || {};

          let matchedEntity: any = null;
          let matchedIndex = -1;

          for (let i = 0; i < entities.length; i++) {
            const entity = entities[i];
            const localName = this.extractPropertyNameFromIRI(entity.predicate);
            if (entity.predicate === key || localName === key) {
              matchedEntity = entity;
              matchedIndex = i;
              break;
            }
          }

          if (matchedIndex !== -1) {
            entities.splice(matchedIndex, 1);
          }

          // Derive rangeUri / rangeLocalName from propDef.ranges — same
          // logic cleanProperties() already uses for "other properties".
          let rangeUri: any = null;
          let rangeLocalName: any = null;
          if (propDef.ranges?.length === 1) {
            rangeUri = propDef.ranges[0].uri;
            rangeLocalName = propDef.ranges[0].localName;
          } else if (propDef.ranges?.length > 1) {
            rangeUri = propDef.ranges.map((r: any) => r.uri);
            rangeLocalName = propDef.ranges.map((r: any) => r.localName);
          }

          if (matchedEntity) {
            matchedEntity.schema = {
              uri: key,
              label: propDef.label,
              cardinality: propDef.cardinality,
              rangeLocalName,
              rangeUri,
              domainUri: propDef.domainUri,
              domainLocalName: propDef.domainLocalName,
              datatypeCategory: propDef.datatypeCategory,
              datatypeUri: propDef.datatypeUri,
              lang: propDef.lang,
            };
            return matchedEntity;
          }

          // No existing value yet — build a placeholder with a real
          // predicate, so it's tracked from the start.
          const predicateUri = propDef.uri || key;

          const placeholder: any = {
            predicate: predicateUri,
            kind: propDef.kind === 'OBJECT_PROPERTY' ? 'iri' : 'literal',
            values: [],
            schema: {
              uri: predicateUri,
              label: propDef.label,
              cardinality: propDef.cardinality,
              rangeLocalName,
              rangeUri,
              domainUri: propDef.domainUri,
              domainLocalName: propDef.domainLocalName,
              datatypeCategory: propDef.datatypeCategory,
              datatypeUri: propDef.datatypeUri,
              lang: propDef.lang,
            },
          };

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

                    if (propvalue.ranges?.length === 1) {
                        property.rangeUri = propvalue.ranges[0].uri;
                        property.rangeLocalName = propvalue.ranges[0].localName;
                    } else if (propvalue.ranges?.length > 1) {
                        property.rangeUri = propvalue.ranges.map((r: any) => r.uri);
                        property.rangeLocalName = propvalue.ranges.map((r: any) => r.localName);
                    } else {
                        property.rangeUri = null;
                        property.rangeLocalName = null;
                    }

                      property.domainUri = propvalue.domainUri;
                      property.domainLocalName = propvalue.domainLocalName;
                      property.datatypeCategory = propvalue.datatypeCategory;
                      property.datatypeUri = propvalue.datatypeUri;
                      property.lang = propvalue.lang;
                  }
              }
          }
      }

      this.checkIfEntityPropertyIsInMainProperties(
          selectedEntity,
          ontologyLabels
      );

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


  private expandWithSuperClasses(typeIris: Iterable<string>, ontologyLabels: any): Set<string> {
    const hierarchyMap: Record<string, string[]> = {};
    for (const ontologyData of Object.values<any>(ontologyLabels)) {
      const h = ontologyData?.hierarchy?.value;
      if (h && typeof h === 'object') {
        Object.assign(hierarchyMap, h);
      }
    }

    const result = new Set<string>(typeIris);
    const toVisit: string[] = [...result];

    while (toVisit.length) {
      const current = toVisit.pop()!;
      const superClasses = hierarchyMap[current];
      if (!superClasses) continue;

      for (const sup of superClasses) {
        if (!result.has(sup)) {
          result.add(sup);
          toVisit.push(sup);
        }
      }
    }

    return result;
  }


  ngOnInit(): void {
    this.ontologyLabels = this.data.ontologyLabels;
    this.selectedEntityId = this.data.selectedEntityId;

    console.log("Dialog data:", this.data);
    if (this.selectedEntityId) {
      this.gestionRessourceService
        .getEntityDetails(this.selectedEntityId)
        .subscribe({
          next: (data) => {
            this.selectedEntity = data;

            this.buildEntityDetails(data.properties, this.ontologyLabels);
            this.buildTypesChips(data.types, this.ontologyLabels);

            // IMPORTANT: enrich mainProperties first
            this.cleanProperties(this.ontologyLabels, this.selectedEntity);

            // THEN build the object used by the template
            this.buildMainProperties(this.ontologyLabels);

            this.cdr.detectChanges();
          },
          error: (err) => console.error("ERROR:", err)
        });
    }

    this.ontologyEntries = Object.entries(this.ontologyLabels)
    .map(([key, value]) => ({ key, value }))
    .sort(this.ontologyOrderComparator); 

    this.onTabChange(0); // Select the first tab (Rico) by default
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

  seeProperty(property: any) {
    console.log("See property  :", property);
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
          .filter((v: any) => v && v.value != null && v.source != 'external')
          .map((v: any) => ({
            value: v.value,
            datatype: v.datatype ?? null,
            lang: v.lang ?? null
          }))
      }))

    const payload = {
      types: this.allEntityTypesChips
        .filter(type => type.source === 'internal')
        .map(type => type.iri),
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


  onTabChange(index: number) {
    const entry = this.ontologyEntries[index];
    if (!entry) {
      console.warn('Ontology not ready yet', index, this.ontologyEntries);
      return;
    }
    this.selectedOntologyTab = entry ; 
    console.log(entry.key, entry.value); // entry.value.name, entry.value.properties, etc.
  }


  private getUsedPredicateUris(ontologyEntry: any): Set<string> {
    const used = new Set<string>();

    // 1. Predicates already shown in "main properties" (per type)
    const mainProperties = ontologyEntry?.mainProperties;
    if (mainProperties && typeof mainProperties === 'object') {
      for (const propList of Object.values<any>(mainProperties)) {
        if (!Array.isArray(propList)) continue;
        for (const item of propList) {
          const uri = item?.predicate || item?.schema?.uri;
          if (uri) used.add(uri);
        }
      }
    }

    // 2. Predicates already shown in "other properties" (entities)
    const entities = ontologyEntry?.entities;
    if (Array.isArray(entities)) {
      for (const entity of entities) {
        if (entity?.predicate) used.add(entity.predicate);
      }
    }

    return used;
  }

  addAssociation(): void {
    const baseTypes = this.selectedEntity.types.map((t: any) => t.iri);
    const typeSet = this.expandWithSuperClasses(baseTypes, this.ontologyLabels);

    console.log("BASE TYPES : ", baseTypes);
    console.log("ALL TYPES : ", typeSet);

    const usedPredicates = this.getUsedPredicateUris(this.selectedOntologyTab.value);

    const matches = this.selectedOntologyTab.value.properties.value.filter(
      (p: any) =>
        p.domainUri !== null &&
        typeSet.has(p.domainUri) &&
        !usedPredicates.has(p.uri)
    );


    const dialogRef = this.dialog.open(RicoPropertiesComponent, {
      width: '600px',
      height: '350px',
      data: { 
        predicates: matches,
        predefinedRanges: this.selectedOntologyTab.value.mainTypes.value,
        removeProperty : this.selectedOntologyTab.value.removeProperty,
        hierarchy: this.selectedOntologyTab.value.hierarchy
        
      }
    });

    dialogRef.afterClosed().subscribe((selectedPredicate: any) => {
      if (selectedPredicate && selectedPredicate.uri) {
        this.addPropertyPlaceholder({
          ...selectedPredicate,
          source: 'internal',
          datasourceShortName: 'internal'
        });
      }
    });
  }

  private addPropertyPlaceholder(predicateDef: any): void {
    if (!this.selectedEntity.properties) {
      this.selectedEntity.properties = [];
    }

    const predicateUri = predicateDef.uri;

    // Don't duplicate the property if it's already on the entity
    let existing = this.selectedEntity.properties.find(
      (p: any) => p.predicate === predicateUri
    );

    const kind = predicateDef.kind === 'OBJECT_PROPERTY' ? 'iri' : 'literal';

    if (existing) {
      if (!existing.values || existing.values.length === 0) {
        existing.values = [{
          value: null,
          datatype: predicateDef.datatypeUri ?? null,
          lang: null,
          name: '',
          source: 'internal',
          datasourceShortName: 'internal'
        }];
      } else {
        existing.values.push({
          value: null,
          datatype: predicateDef.datatypeUri ?? null,
          lang: null,
          name: '',
          source: 'internal',
          datasourceShortName: 'internal'
        });
      }
    } else {
      let rangeUri = '';
      let rangeLocalName = '';
      if(predicateDef.ranges && predicateDef.ranges.length === 1){
        console.log("HERRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRE");
        rangeUri = predicateDef.ranges[0].uri;
        rangeLocalName = predicateDef.ranges[0].localName;
      }
      console.log("RANGE URI : ", rangeUri);

      this.selectedEntity.properties.push({
        predicate: predicateUri,
        kind,
        rangeUri : rangeUri,
        rangeLocalName : rangeLocalName,
        values: [{
          value: null,
          datatype: predicateDef.datatypeUri ?? null,
          lang: null,
          name: '',
          source: 'internal',
          editing : true,
          datasourceShortName: 'internal'
        }]
      });
    }

    // Rebuild derived views so the new property shows up correctly
    // (either inside mainProperties for its type, or in the ontology's "entities" / other-properties list)
    this.buildEntityDetails(this.selectedEntity.properties, this.ontologyLabels);
    this.buildMainProperties(this.ontologyLabels);
    this.cleanProperties(this.ontologyLabels, this.selectedEntity);

    this.cdr.markForCheck();
  }

  getEntitiesByTypes(rangeUris: string[]): void {
    this.listOfAllRanges = [];

    if (!rangeUris || rangeUris.length === 0) {
      return;
    }

    const uniqueRangeUris = [...new Set(rangeUris)];

    forkJoin(
      uniqueRangeUris.map(uri =>
        this.gestionRessourceService.getAllEntitiesByType(uri)
      )
    ).subscribe({
      next: (results) => {
        this.listOfAllRanges = results.flat();

        //remove duplicate entities by IRI
        // this.listOfAllRanges = Array.from(
        //   new Map(
        //     this.listOfAllRanges.map(entity => [entity.iri, entity])
        //   ).values()
        // );
      },
      error: (err) => {
        console.error("Error fetching possible ranges: ", err);
      }
    });
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
