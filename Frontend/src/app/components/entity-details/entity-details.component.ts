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
    FileViewerComponent
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
  detailTab: string = 'rico';
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


  constructor(@Inject(MAT_DIALOG_DATA) public data: any) {
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

  changeSelectedEntity(entityIri : string) {
    if (entityIri) {
      this.stack.push(this.selectedEntity);
      this.gestionRessourceService.getEntityDetails(entityIri).subscribe({
        next: (data) => {
          console.log("DATA:", data);
          this.selectedEntity = data;
          // this.getEntityPropertiesDict();
          this.buildEntityDetails(this.selectedEntity.properties, this.ontologyLabels);

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


  ngOnInit(): void {
    this.detailTab = 'rico';

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
    this.detailTab = 'rico';
    this.cdr.markForCheck();
  }

  closeDetail() {
    console.log("CLOSING Details view ");
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

  editEntity() : void {
    const payload = {
      properties: this.entityPropertiesDict.map(({ value, predicate, kind }) => ({
        value,
        predicate,
        kind
      }))
    };
    this.gestionRessourceService.editEntity(this.selectedEntity.iri, payload).subscribe({
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
        console.log('Entity updated:', this.selectedEntity.iri);
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
