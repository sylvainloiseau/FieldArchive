import { Component, OnInit, Inject, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule, KeyValue } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {MatTabsModule} from '@angular/material/tabs';

import {GestionRessourcesService} from '../../services/gestion-ressources.service';
import { MatDialogModule, MatDialogRef, MatDialog, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { Stack } from '../../shared/utils/stack';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../shared/ui/confirm-dialog.component';
import { SourceBadgeComponent } from '../../shared/ui/source-badge.component';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { FileViewerComponent } from '../file-viewer/file-viewer.component';
import { CreateEntityComponent } from '../create-entity/create-entity.component';
import {RicoPropertiesComponent} from '../rico-properties/rico-properties.component';
import { RangeSelectionDialogComponent } from '../range-selection-dialog/range-selection-dialog.component';

import {MatChipsModule} from '@angular/material/chips';
import {MatButtonModule} from '@angular/material/button';
import {MatTooltipModule} from '@angular/material/tooltip';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';

/** The entities offered by the drop-down of an IRI property, loaded once per set of ranges. */
interface RangeOptions {
  loading: boolean;
  groups: { typeIri: string; typeLabel: string; entities: any[] }[];
  all: any[];
}

@Component({
  selector: 'app-entity-details',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatDialogModule,
    MatSnackBarModule,
    MatChipsModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    SourceBadgeComponent
  ],
  templateUrl: './entity-details.component.html',
  styleUrl: './entity-details.component.scss'
})
export class EntityDetailsComponent implements OnInit {

  private readonly RICO_NAME_PREDICATE = 'https://www.ica.org/standards/RiC/ontology#name';

  selectedEntityId: string = "";
  ontologyLabels: Record<string, any>[] = [];

  stack = new Stack<any>();

  selectedEntity : any = null ;

  allEntityTypesChips : any[] = [];

  private gestionRessourceService = inject(GestionRessourcesService);
  private cdr = inject(ChangeDetectorRef);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  /** Options of the IRI properties' drop-downs, keyed by their sorted range IRIs (see rangeKey). */
  private rangeOptions = new Map<string, RangeOptions>();

  ontologyEntries: { key: string; value: any }[] = [];
  selectedOntologyTab : {key : string , value : any} = {key : "" , value : null} ;


  constructor(
    @Inject(MAT_DIALOG_DATA) public data: any,
    private dialogRef: MatDialogRef<EntityDetailsComponent>
  ) {
    this.ontologyLabels = data.ontologyLabels;
    this.selectedEntityId = data.selectedEntityId;
  }

  // Save the edited value of a literal property; a blank value is removed rather than saved
  saveLiteralEdit(propValue: any, property?: any): void {
    propValue.editing = false;
    if (property && this.isBlank(propValue.value)) {
      property.values = property.values.filter((v: any) => v !== propValue);
    }
    this.editEntity();
  }

  // Toggle the editing state of a literal property
  toggleLiteralEdit(propValue: any, property?: any): void {
    if (propValue.editing) {
      this.saveLiteralEdit(propValue, property);
    } else {
      propValue.editing = true;
    }
  }

  private isBlank(value: any): boolean {
    return value == null || String(value).trim() === '';
  }

  /**
   * The entity's name, for the title and the messages: its rico:name, else another of its
   * label properties, else its IRI.
   */
  get displayName(): string {
    for (const entry of Object.values<any>(this.ontologyLabels ?? {})) {
      const name = (entry?.nameProperty?.values ?? []).find((v: any) => !this.isBlank(v?.value));
      if (name) return name.value;
    }
    const labelPredicates = [
      'http://www.w3.org/2000/01/rdf-schema#label',
      'http://purl.org/dc/terms/title',
      'http://xmlns.com/foaf/0.1/name'
    ];
    for (const predicate of labelPredicates) {
      const p = (this.selectedEntity?.properties ?? []).find((x: any) => x?.predicate === predicate);
      const v = (p?.values ?? []).find((x: any) => !this.isBlank(x?.value));
      if (v) return v.value;
    }
    return this.selectedEntity?.iri ?? '';
  }

  /** Number of the entity's properties with a value in one ontology tab (shown in the tab label). */
  propertyCount(ontologyEntry: any): number {
    const hasValue = (p: any) => (p?.values ?? []).some((v: any) => !this.isBlank(v?.value));
    let n = (ontologyEntry?.entities ?? []).filter(hasValue).length;
    for (const list of Object.values<any>(ontologyEntry?.main_Properties ?? {})) {
      if (Array.isArray(list)) n += list.filter(hasValue).length;
    }
    if (hasValue(ontologyEntry?.nameProperty)) n++;
    return n;
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 4000 });
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
        this.notify('IRI copied.');
      }).catch(err => {
        console.error('Failed to copy:', err);
        this.notify('Could not copy the IRI: ' + err);
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

    const selectedRange = this.rangeOptionsOf(property)?.all.find(t => t.iri === selectedIri);
    (event.target as HTMLSelectElement).value = '';
    if (!selectedRange) return;

    this.addIriValue(property, selectedRange.iri, selectedRange.label);
    this.editEntity();
  }

  /** Adds a link to an entity as a new internal value of an IRI property. */
  private addIriValue(property: any, iri: string, name: string): void {
    const newValue = {
      kind: 'iri',
      value: iri,
      name,
      datatype: null,
      lang: null,
      source: 'internal',
      datasourceShortName: 'internal'
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
  }

  onRangeDeleted(range: any, property: any): void {

    const rangeValue: string = range?.value ?? '';

    // a value that was never saved (empty, or being typed) is just dropped
    if (rangeValue.trim() === '' || (range.editing && property.kind === 'literal')) {
      property.values = property.values.filter((v: any) => v !== range);
      return
    }

    const propertyLabel = property.key ?? property.schema?.label ?? this.extractPropertyNameFromIRI(property.predicate);
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '460px',
      data: {
        title: 'Delete this value?',
        message: 'The value is removed from the internal data source.',
        details: [
          { label: 'Entity', value: this.displayName },
          { label: 'Property', value: propertyLabel },
          { label: 'Value', value: range?.name || rangeValue }
        ],
        confirmLabel: 'Delete',
        warn: true
      } as ConfirmDialogData
    });

    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        property.values = property.values.filter((v: any) => v !== range);

        console.log("Values after deletion for this rangeIri:", rangeValue, property.values);

        this.editEntity();


      }
    });


  }

  /** "Create" next to an IRI property's drop-down: creates an entity of its range and links it. */
  openCreateEntityDialogForRange(property: any): void {
  const rangeUris: string[] = this.rangeUrisOf(property);
  const localNames = property.schema?.rangeLocalName ?? property.rangeLocalName;
  const rangeLocalNames: string[] = Array.isArray(localNames) ? localNames : [localNames];

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
        this.openCreateEntityDialogWithType(selectedRange.uri, property);
      }
    });
    return;
  }

  // Single possible type -> go straight to entity creation
  this.openCreateEntityDialogWithType(rangeUris[0], property);
}

private openCreateEntityDialogWithType(rangeTypeIRI: string, property: any): void {
  this.dialog.open(CreateEntityComponent, {
    width: '600px',
    data: {
      fullType: rangeTypeIRI,
      ontologiesData: this.ontologyLabels
    }
  }).afterClosed().subscribe((created: any) => {
    // the dialog closes with the created entity (RdfEntityDto) on success
    if (!created?.iri) return;
    const nameProp = (created.properties ?? []).find((p: any) => p.predicate === this.RICO_NAME_PREDICATE);
    const name = nameProp?.values?.[0]?.value ?? created.iri;
    this.rangeOptions.clear();
    this.addIriValue(property, created.iri, name);
    this.editEntity();
    this.preloadRangeOptions();
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
          this.cleanProperties(this.ontologyLabels, this.selectedEntity);
          this.extractNameProperty(this.ontologyLabels);
          this.rangeOptions.clear();
          this.preloadRangeOptions();
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

    // One chip per type, also for types outside the configured ontologies: editEntity() sends
    // the internal chips back as the complete list of the entity's internal types.
    for (let type of entityTypes) {
      const typeIri = type.iri; // was: type (plain string) before

      const ontology = Object.entries(ontologies).find(([key]) => typeIri.startsWith(key));
      this.allEntityTypesChips.push(
        {
          "label": ontology
            ? ontology[1].name + ":" + this.extractPropertyNameFromIRI(typeIri)
            : this.extractPropertyNameFromIRI(typeIri),
          "iri": typeIri,
          "source": type.source,
          "datasourceShortName": type.datasourceShortName,
          "ontology": ontology ? ontology[0] : null
        }
      );
    }
  }

  removeChip(typeUrl: string) {
    this.allEntityTypesChips = this.allEntityTypesChips.filter(t => t.iri !== typeUrl);
    this.editEntity();
  }

  /**
   * rico:name is special: it is always rendered in the dedicated "Name" field at the top
   * of the form, whether or not configuration.json lists it among the main properties.
   */
  private isRicoName(key: string, namespaceUri: string): boolean {
    return key === this.RICO_NAME_PREDICATE
      || (key === 'name' && this.RICO_NAME_PREDICATE.startsWith(namespaceUri));
  }

  /**
   * Local names of the entity's types in one ontology, plus those of their superclasses:
   * a rico:Person gets the main properties configured for rico:Agent.
   */
  private applicableTypeLocalNames(namespaceUri: string, ontologyLabels: any): Set<string> {
    const typeIris = this.expandWithSuperClasses(this.allEntityTypesChips.map(t => t.iri), ontologyLabels);
    const names = new Set<string>();
    for (const iri of typeIris) {
      if (iri.startsWith(namespaceUri)) names.add(this.extractPropertyNameFromIRI(iri));
    }
    return names;
  }

  /**
   * Builds, for the entity being edited, `main_Properties` (the "Main Properties" block):
   * for each configured type that the entity has — directly or through a superclass — the
   * configured properties, each either the entity's own property (moved out of the "Other
   * Properties" list) or an empty placeholder. The configuration itself (`mainProperties`)
   * is only read, never modified, so nothing carries over from one entity to the next.
   * rico:name is never placed here: it has its own field (see extractNameProperty).
   */
  checkIfEntityPropertyIsInMainProperties(selectedEntity: any, ontologyLabels: any) {

    for (const [namespaceUri, ontologyData] of Object.entries<any>(ontologyLabels)) {

      const mainPropertyMap = ontologyData?.mainProperties;
      const propertyDefs: any[] = ontologyData?.properties?.value || [];
      const entities: any[] = ontologyData?.entities || [];

      ontologyData.main_Properties = {};

      if (!mainPropertyMap || typeof mainPropertyMap !== 'object') {
        continue;
      }

      const applicableTypes = this.applicableTypeLocalNames(namespaceUri, ontologyLabels);
      // a property configured for both a type and its superclass is shown once
      const placed = new Set<string>();

      for (const [typeName, propList] of Object.entries<any>(mainPropertyMap)) {

        if (!Array.isArray(propList)) {
          continue;
        }
        const entityType = this.extractPropertyNameFromIRI(typeName);
        if (!applicableTypes.has(entityType)) {
          continue;
        }

        const built: any[] = [];
        for (const propIdentifier of propList) {

          // Reduce to a plain string key — a predicate URI or local name — whether the
          // config gave us a string or an object.
          const key = (typeof propIdentifier === 'object' && propIdentifier !== null)
            ? (propIdentifier.predicate || propIdentifier.uri)
            : propIdentifier;

          if (!key || this.isRicoName(key, namespaceUri)) {
            continue; // rico:name has its own field at the top of the form
          }

          const propDef = propertyDefs.find((p: any) =>
            p.uri === key ||
            this.extractPropertyNameFromIRI(p.uri) === key
          ) || {};

          const predicateUri = propDef.uri || key;
          if (placed.has(predicateUri)) {
            continue;
          }
          placed.add(predicateUri);

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

          const schema = {
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
          };

          if (matchedEntity) {
            matchedEntity.schema = schema;
            built.push(matchedEntity);
          } else {
            // No existing value yet — a placeholder with a real predicate, so it's tracked from the start.
            built.push({
              predicate: predicateUri,
              kind: propDef.kind === 'OBJECT_PROPERTY' ? 'iri' : 'literal',
              values: [],
              schema,
            });
          }
        }

        if (built.length) {
          ontologyData.main_Properties[entityType] = [
            ...(ontologyData.main_Properties[entityType] || []),
            ...built
          ];
        }
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
                        property.rangeUri = [propvalue.ranges[0].uri];
                        property.rangeLocalName = [propvalue.ranges[0].localName];
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
      value.nameProperty = null;
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

  /**
   * Pull rico:name out of the regular property lists so it can be rendered as
   * its own "Name" section at the top of the form. The predicate's namespace
   * only ever matches the RiC-O bucket built by buildEntityDetails(), so this
   * naturally shows up on the RiC-O tab only.
   */
  extractNameProperty(ontologyLabels: any): void {

    for (const [namespaceUri, ontologyData] of Object.entries<any>(ontologyLabels)) {

      const entities: any[] = ontologyData?.entities || [];
      const propertyDefs: any[] = ontologyData?.properties?.value || [];

      let nameProperty: any = null;

      // "Other Properties" backing array
      const index = entities.findIndex(
        (entity: any) => entity?.predicate === this.RICO_NAME_PREDICATE
      );
      if (index !== -1) {
        nameProperty = entities.splice(index, 1)[0];
      }

      // Defensive: if configuration.json ever lists `name` under mainProperties,
      // splice it out of there too so it is never rendered twice.
      const mainProperties = ontologyData?.main_Properties;
      if (mainProperties && typeof mainProperties === 'object') {
        for (const propList of Object.values<any>(mainProperties)) {
          if (!Array.isArray(propList)) {
            continue;
          }
          for (let i = propList.length - 1; i >= 0; i--) {
            if (propList[i]?.predicate === this.RICO_NAME_PREDICATE) {
              const [spliced] = propList.splice(i, 1);
              nameProperty = nameProperty || spliced;
            }
          }
        }
      }

      // Wherever the display lists put it, the entity's own rico:name is the one to show:
      // never create a second, empty rico:name next to an existing one (on save, the
      // empty one would overwrite the real name).
      if (!nameProperty && this.RICO_NAME_PREDICATE.startsWith(namespaceUri)) {
        nameProperty = (this.selectedEntity?.properties || []).find(
          (p: any) => p?.predicate === this.RICO_NAME_PREDICATE
        ) || null;
      }

      // The entity carries no rico:name triple (creation always sets one, but
      // imported entities may lack it) — render an empty, editable field.
      if (!nameProperty && this.RICO_NAME_PREDICATE.startsWith(namespaceUri)) {
        const propDef = propertyDefs.find(
          (p: any) => p.uri === this.RICO_NAME_PREDICATE
        ) || {};

        nameProperty = {
          predicate: this.RICO_NAME_PREDICATE,
          kind: 'literal',
          key: this.extractPropertyNameFromIRI(this.RICO_NAME_PREDICATE),
          values: [{
            value: null, // not saved until the user types a name (editEntity drops null values)
            datatype: propDef.datatypeUri ?? 'http://www.w3.org/2001/XMLSchema#string',
            lang: null,
            name: '',
            source: 'internal',
            editing: true,
            datasourceShortName: 'internal'
          }],
          schema: {
            uri: this.RICO_NAME_PREDICATE,
            label: propDef.label,
            cardinality: propDef.cardinality,
            rangeLocalName: propDef.ranges?.length === 1 ? propDef.ranges[0].localName : null,
            rangeUri: propDef.ranges?.length === 1 ? propDef.ranges[0].uri : null,
            domainUri: propDef.domainUri,
            domainLocalName: propDef.domainLocalName,
            datatypeCategory: propDef.datatypeCategory,
            datatypeUri: propDef.datatypeUri,
            lang: propDef.lang,
          },
        };

        // Track it on the entity itself, otherwise editEntity() — which
        // serializes selectedEntity.properties — would not persist the name.
        if (this.selectedEntity?.properties) {
          this.selectedEntity.properties.push(nameProperty);
        }
      }

      ontologyData.nameProperty = nameProperty;
    }
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

            // enrich the properties and build the per-entity "Main Properties" (main_Properties)
            this.cleanProperties(this.ontologyLabels, this.selectedEntity);

            // FINALLY pull rico:name out into its own top-level section
            this.extractNameProperty(this.ontologyLabels);

            this.preloadRangeOptions();
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
    this.cleanProperties(this.ontologyLabels, this.selectedEntity);
    this.extractNameProperty(this.ontologyLabels);
    this.rangeOptions.clear();
    this.preloadRangeOptions();
    this.cdr.markForCheck();
  }

  closeDetail() {
    this.dialogRef.close();
  }

  deleteEntity() {
    if (!this.selectedEntity) return;

    const name = this.displayName;
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '460px',
      data: {
        title: 'Delete this entity?',
        message: 'Its statements in the internal data source are deleted. This cannot be undone.',
        details: [
          { label: 'Name', value: name },
          { label: 'IRI', value: this.selectedEntity.iri, mono: true }
        ],
        confirmLabel: 'Delete',
        warn: true
      } as ConfirmDialogData
    });

    dialogRef.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {

        this.gestionRessourceService.deleteEntity(this.selectedEntity.iri).subscribe({
          next: () => {
            this.notify(`${name} deleted.`);
            this.closeDetail();
          },
          error: (err : any) => {
            console.error('Delete failed:', err)
            this.notify(`Could not delete ${name}.`);
          }
        });
      }
    });
  }

  editEntity(): void {
    // One entry per predicate: the backend replaces a predicate's values entry by entry,
    // so two entries for the same predicate would let the last one erase the first.
    const byPredicate = new Map<string, { predicate: string; kind: string; values: any[] }>();
    for (const p of (this.selectedEntity.properties || [])) {
      if (!p || !p.predicate || !p.kind) continue; // drop anything malformed/corrupted
      const values = (p.values || [])
        .filter((v: any) => v && v.value != null && v.source != 'external')
        .filter((v: any) => p.kind !== 'literal' || String(v.value).trim() !== '') // no empty literals
        .map((v: any) => ({
          value: v.value,
          datatype: v.datatype ?? null,
          lang: v.lang ?? null
        }));
      const existing = byPredicate.get(p.predicate);
      if (existing) {
        for (const v of values) {
          if (!existing.values.some(e => e.value === v.value && e.lang === v.lang && e.datatype === v.datatype)) {
            existing.values.push(v);
          }
        }
      } else {
        byPredicate.set(p.predicate, { predicate: p.predicate, kind: p.kind, values });
      }
    }
    const properties = [...byPredicate.values()];

    const payload = {
      types: this.allEntityTypesChips
        .filter(type => type.source === 'internal')
        .map(type => type.iri),
      properties
    };

    console.log("ENTITY TO BE UPDATED : ", payload);

    this.gestionRessourceService.editEntity(this.selectedEntity.iri, payload).subscribe({
      next: () => {
        this.notify(`${this.displayName} updated.`);
      },
      error: (err) => {
        console.error('Edit failed:', err);
        this.notify(`Could not update ${this.displayName}.`);
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
    this.preloadRangeOptions();
  }


  private getUsedPredicateUris(ontologyEntry: any): Set<string> {
    const used = new Set<string>();

    // 0. rico:name, shown in its own field at the top
    if (ontologyEntry?.nameProperty?.predicate) used.add(ontologyEntry.nameProperty.predicate);

    // 1. Predicates already shown in "main properties" for this entity (per type)
    const mainProperties = ontologyEntry?.main_Properties;
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

  /** The properties offered by "Add property" depend on the entity's type in that ontology. */
  hasTypeInOntology(namespace: string): boolean {
    return !!namespace && this.allEntityTypesChips.some(t => t.iri?.startsWith(namespace));
  }

  addAssociation(): void {
    if (!this.hasTypeInOntology(this.selectedOntologyTab?.key)) return;
    const baseTypes = this.allEntityTypesChips.map((t: any) => t.iri);
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
      maxHeight: '85vh',
      data: {
        predicates: matches,
        predefinedRanges: this.selectedOntologyTab.value.mainTypes?.value ?? [],
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
    this.cleanProperties(this.ontologyLabels, this.selectedEntity);
    this.extractNameProperty(this.ontologyLabels);

    this.cdr.markForCheck();
  }

  /** The range IRIs of an IRI property (main properties keep them in `schema`). */
  rangeUrisOf(property: any): string[] {
    const r = property?.schema?.rangeUri ?? property?.rangeUri;
    return (Array.isArray(r) ? r : [r]).filter((u: any) => !!u);
  }

  private rangeKey(property: any): string {
    return [...new Set(this.rangeUrisOf(property))].sort().join(' ');
  }

  /** The options of a property's drop-down, or undefined while never requested. */
  rangeOptionsOf(property: any): RangeOptions | undefined {
    return this.rangeOptions.get(this.rangeKey(property));
  }

  /** Loads a property's drop-down options once per set of ranges. */
  loadRangeOptions(property: any): void {
    const key = this.rangeKey(property);
    if (!key || this.rangeOptions.has(key)) return;
    const options: RangeOptions = { loading: true, groups: [], all: [] };
    this.rangeOptions.set(key, options);
    this.gestionRessourceService.getAllEntitiesByType(key.split(' '), true).subscribe({
      next: (res) => {
        options.all = res;
        options.groups = this.buildGroupedRanges(res);
        options.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error("Error fetching possible ranges: ", err);
        options.loading = false;
        this.rangeOptions.delete(key); // retried on the next focus
      }
    });
  }

  /** Loads in advance the drop-down options of the IRI properties of the current tab. */
  private preloadRangeOptions(): void {
    const entry = this.selectedOntologyTab?.value;
    if (!entry || !this.selectedEntity) return;
    const properties: any[] = [...(entry.entities ?? [])];
    for (const list of Object.values<any>(entry.main_Properties ?? {})) {
      if (Array.isArray(list)) properties.push(...list);
    }
    for (const p of properties) {
      if (p?.kind === 'iri') this.loadRangeOptions(p);
    }
  }

  /**
   * Groups entities by their own asserted type(s) among those matched by the query
   * (`matchedTypes`, set by the backend), sorted alphabetically by type label. An entity
   * asserted with several matching types appears once per matching group.
   */
  private buildGroupedRanges(entities: any[]): { typeIri: string; typeLabel: string; entities: any[] }[] {
    const groups = new Map<string, { typeIri: string; typeLabel: string; entities: any[] }>();

    for (const entity of entities) {
      const types: string[] = entity.matchedTypes && entity.matchedTypes.length
        ? entity.matchedTypes
        : [''];

      for (const typeIri of types) {
        if (!groups.has(typeIri)) {
          groups.set(typeIri, {
            typeIri,
            typeLabel: typeIri ? this.extractPropertyNameFromIRI(typeIri) : 'Other',
            entities: []
          });
        }
        groups.get(typeIri)!.entities.push(entity);
      }
    }

    return Array.from(groups.values()).sort((a, b) => a.typeLabel.localeCompare(b.typeLabel));
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

  /** Previews the file a literal value points to; for an internal value, the chosen file replaces it. */
  openFileViewer(propValue: any, filePath: string) {
    if (!filePath ) return ;
    const editable = propValue.source === 'internal';

    const dialogRef = this.dialog.open(FileViewerComponent, {
      width: '1000px',
      maxWidth: '95vw',
      data: { path: filePath, readOnly: !editable }
    });

    dialogRef.afterClosed().subscribe((newFilePath) => {
      if (editable && newFilePath && newFilePath !== propValue.value) {
        propValue.value = newFilePath;
        this.editEntity();
        this.cdr.markForCheck();
      }
    });
  }

}
