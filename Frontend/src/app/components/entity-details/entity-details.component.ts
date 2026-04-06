import { Component, OnInit, Input, OnChanges, SimpleChanges, inject, ChangeDetectorRef, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Entity } from '../../models/ressource';
import { allEntities } from '../../models/ressource';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';

import {GestionRessourcesService} from '../../services/gestion-ressources.service';
import { MatDialogModule, MatDialogRef, MatDialog, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { Stack } from '../../shared/utils/stack';
import { ConfirmDeleteDialogComponent, ConfirmDeleteDialogData } from '../confirm-delete-dialog/confirm-delete-dialog.component';
import { ConfirmDeletePropertyData, ConfirmDeletePropertyComponent } from '../confirm-delete-property/confirm-delete-property.component';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {ONTOLOGY_LABELS} from '../../models/ontology-labels';


@Component({
  selector: 'app-entity-details',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, MatDialogModule, MatSnackBarModule],
  templateUrl: './entity-details.component.html',
  styleUrl: './entity-details.component.scss'
})
export class EntityDetailsComponent implements OnInit, OnChanges {

  @Input() selectedEntityId: string = "";  
  @Output() close = new EventEmitter<void>();

  stack = new Stack<any>();

  selectedEntity : any = null ;
  entityPropertiesDict : any[] = [];
  detailTab: 'ric' | 'foaf' | 'metadata' = 'ric';
  myNewEntites : Entity[] = [];
  newAssociation: {
  mode: 'existing' | 'new' | null;  // which sub-mode
  predicate: string;                 // for 'existing': full predicate URI
  ontologyUrl: string;               // for 'new': selected ontology base URL
  customPredicate: string;           // for 'new': user-typed predicate name
  kind: 'literal' | 'iri';
  value: string;
  } | null = null;

  private gestionRessourceService = inject(GestionRessourcesService);
  private cdr = inject(ChangeDetectorRef); 
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);


  // constructor(private gestionRessourceService : GestionRessourcesService ) {}

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

  ngOnChanges(changes: SimpleChanges) {

    if (changes['selectedEntityId'] && this.selectedEntityId) {
      this.gestionRessourceService
        .getEntityDetails(this.selectedEntityId)
        .subscribe({
          next: (data) => {
            console.log("DATA:", data);
            this.selectedEntity = data;
            this.getEntityPropertiesDict();
            this.cdr.markForCheck();
          },
          error: (err) => console.error("ERROR:", err)
        });
    }
  }

  getEntityPropertiesDict() : void {
    const entityProperties = this.selectedEntity.properties || [];
    let entityPropertiesDict : any [] = [];
    if (entityProperties.length > 0) {
      for (let prop of entityProperties) {
        if (prop.predicate.includes('#')) {
          let propertyName = prop.predicate.substring(prop.predicate.lastIndexOf('#') + 1, prop.predicate.length);
          entityPropertiesDict.push({ key: propertyName, value: prop.value, kind: prop.kind, predicate : prop.predicate  });
        }
        else {
          let propertyName = prop.predicate.substring(prop.predicate.lastIndexOf('/') + 1, prop.predicate.length);
          entityPropertiesDict.push({ key: propertyName, value: prop.value, kind : prop.kind, predicate : prop.predicate  });
        }
      }

    }
    console.log("I'm here ", entityPropertiesDict);
    this.entityPropertiesDict = entityPropertiesDict;
  }

  changeSelectedEntity(entityIri : string) {
    if (entityIri) {
      const entityKey = entityIri.substring(entityIri.lastIndexOf('/') + 1, entityIri.length);
      console.log("Reference entity key : ",entityKey);
      // this.previousSelectedEntity = this.selectedEntity;
      this.stack.push(this.selectedEntity);
      this.gestionRessourceService.getEntityDetails(entityKey).subscribe({
        next: (data) => {
          console.log("DATA:", data);
          this.selectedEntity = data;
          this.getEntityPropertiesDict();
          this.cdr.markForCheck();
        },
        error: (err) => console.error("ERROR:", err)
      });
    }

  }


  ngOnInit(): void {

    this.detailTab = 'ric';
    this.myNewEntites = allEntities;
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

  getEntityById(id: number): Entity | undefined {
    const id_str = id.toString();
    return allEntities.find(entity => entity.id === id_str);
  }

  selectEntity(entity: any) {
    this.selectedEntity = entity;
    this.getEntityPropertiesDict(); // ← ajouter
    this.detailTab = 'ric';
    this.cdr.markForCheck();
  }

  closeDetail() {
    console.log("CLOSING Details view ");
    this.close.emit();

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

        this.gestionRessourceService.deleteEntity(this.selectedEntity.entityKey).subscribe({
          next: () => {
            this.snackBar.open(
              `"${this.selectedEntity.titre}" was successfully deleted.`,
              'Close',
              {
                duration: 5000,
                horizontalPosition: 'right',
                verticalPosition: 'bottom',
                panelClass: ['snackbar-success']
              }
            );
            console.log('Entity deleted:', this.selectedEntity.entityKey);
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

  editEntity() : void {
    const payload = {
      properties: this.entityPropertiesDict.map(({ value, predicate, kind }) => ({
        value,
        predicate,
        kind
      }))
    };
    this.gestionRessourceService.editEntity(this.selectedEntity.entityKey, payload).subscribe({
      next: () => {
        this.snackBar.open(
          `"${this.selectedEntity.iri}" was successfully updated.`,
          'Close',
          {
            duration: 5000,
            horizontalPosition: 'center',
            verticalPosition: 'top',
            panelClass: ['snackbar-success']
          }
        );
        console.log('Entity updated:', this.selectedEntity.entityKey);
      },
      error: (err) => {
        console.error('Edit failed:', err);
        this.snackBar.open(
          `Failed to update "${this.selectedEntity.iri}".`,
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
    console.log("Edited properties : ", payload);

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


// Computed list of ontology entries for the dropdown
get ontologyEntries(): { label: string; url: string }[] {
  return Object.entries(ONTOLOGY_LABELS).map(([url, label]) => ({ url, label: label as string }));
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

  this.entityPropertiesDict.push({
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
