import {ChangeDetectionStrategy,ChangeDetectorRef, signal, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { trigger, transition, style, animate } from '@angular/animations';

import {MatExpansionModule} from '@angular/material/expansion';
import {MatDividerModule} from '@angular/material/divider';
import {MatListModule} from '@angular/material/list';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { CreateRessourceComponent } from '../create-ressource/create-ressource.component';
import {Entity, EntityType } from '../../models/ressource';

import { EntityDetailsComponent } from '../entity-details/entity-details.component';
import { ListeEntitesComponent } from '../liste-entites/liste-entites.component';

import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import { GestionProjetsComponent } from '../gestion-projets/gestion-projets.component';
import { GestionProjetsService } from '../../services/gestion-projets.service';
import { error } from 'console';

import { OntologyManagerDialogComponent } from '../ontology-manager-dialog/ontology-manager-dialog.component';
import { Router } from '@angular/router';

import { MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-gestion-ressources',
  standalone: true,
  imports: [CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatExpansionModule,
    MatDividerModule,
    MatListModule,
    MatDialogModule,
    EntityDetailsComponent, ListeEntitesComponent],
  templateUrl: './gestion-ressources.component.html',
  styleUrl: './gestion-ressources.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateX(100%)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateX(0)', opacity: 1 }))
      ]),
      transition(':leave', [
        animate('200ms ease-in', style({ transform: 'translateX(100%)', opacity: 0 }))
      ])
    ])
  ]
})
export class GestionRessourcesComponent implements OnInit {
  panelOpenState = signal(false);

  formattedEntities : any[] = [];

  ontologyLabels : any[] = [];

  allRicoClasses : any[] = [];

  projectName : string = '';

  filteredEntityTypes: EntityType[] = [];
  selectedEntity: any | null = null;
  previousSelectedEntity: Entity | null = null;

  selectedType: string | null = null;
  selectedOntology : string | null = null;

  activeView: 'tableau' | 'graphe' | 'sources' | 'sparql' = 'tableau';
  detailTab: 'ric' | 'foaf' | 'metadata' = 'ric';

  showPersonForm: boolean = false;


  constructor(public dialog: MatDialog, private ontologyService: GestionRessourcesService,
    private projetService: GestionProjetsService,
    private cdr: ChangeDetectorRef,
    public  router:         Router

  ) {}

  openOntologyManagerDialog() {
  //   this.dialog.open(OntologyManagerDialogComponent, {
  //     width: '1500px'
  //   });
  }

  ngOnInit() {
    
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

          this.allRicoClasses = ricOClasses;
          console.log("Classes RICO (no duplicates) ", this.allRicoClasses);

        } catch (e) {
          console.error("ERROR in getOntologyLabel:", e);
        }

        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error(err);
      }
    });

    // this.filteredEntities = [...this.allEntities];
    // this.filteredEntityTypes = [...this.entityTypes];
    // this.updateEntityTypeCounts();
    this.detailTab = 'ric';
    
  
    this.ontologyService.getTypes().subscribe({
    next: (data) => {
      console.log("Types d'ontology ", data);
      this.ontologyLabels = this.ontologyService.getOntologyLabel(data);
      console.log("Labels d'ontology ", this.ontologyLabels);
      this.cdr.markForCheck();
    }
    });

    this.projetService.getProject().subscribe({
    next: (data) => {
      this.projectName = data.name;
      this.cdr.markForCheck();
    },
      error: (err) => {
        console.error(err);
      }
      }
    );


  
  }

  getKey(obj: Record<string, any>): string {
    return Object.keys(obj)[0];
  }

  handleChildData(data: any) {
    console.log('Received from child:', data);
      this.selectedEntity = { ...data }; // nouvelle ref
    this.detailTab = 'ric';
    this.cdr.markForCheck();            
  }

  getValuesOfKey(obj: Record<string, any>): any[] {
    const key = this.getKey(obj);
    return obj[key];
  }

  // Update entity type counts based on actual data
  // updateEntityTypeCounts() {
  //   this.entityTypes.forEach(entityType => {
  //     entityType.count = this.allEntities.filter(e => e.type === entityType.type).length;
  //   });
  // }

  // Get total count of entities
  // getTotalCount(): number {
  //   return this.allEntities.length;
  // }

  backToPreviousEntity() {
    if (this.previousSelectedEntity) {
      const prev = this.previousSelectedEntity;
      this.previousSelectedEntity = null;
      this.selectEntity(prev);
    }
  }

  // to be returned to the child component
  // get formattedEntities(): Record<string, any[]> {
  //   return this.selectedType
  //     ? { [this.selectedType]: this.allEntities }
  //     : {};
  // }


  getAllEntitiesByPath(ontology_name : string ,entity_type: string) : void {
    this.selectedType = entity_type;
    this.selectedOntology = ontology_name;
    const typeUrl = this.ontologyService.getTypeUrlByName(ontology_name ?? '');
    if (typeUrl) {
      this.ontologyService.getAllEntitiesByType(typeUrl+entity_type).subscribe({
        next: (data : any) => {
          console.log("Entities for type ", entity_type, " : ", data);  
          // this.allEntities = data;
          // this.formattedEntities = { [ontology_name]: data };
          this.formattedEntities = data;
          this.cdr.markForCheck();

        },
        error: (err : any) => {
          console.error(err);
        }
      });
    }
  }

  // getEntityById(id: number): Entity | undefined {
  //   const id_str = id.toString();
  //   return this.allEntities.find(entity => entity.id === id_str);
  // }

  // Select entity
  selectEntity(entity: Entity) {
    this.selectedEntity = entity;
  }


  // Edit entity
  editEntity(entity: Entity, event: Event) {
    event.stopPropagation(); // Prevent row click
    console.log('Edit entity:', entity);
    // TODO: Implement edit modal
  }

  // Save entity
  saveEntity() {
    if (this.selectedEntity) {
      console.log('Saving entity:', this.selectedEntity);
      // TODO: Implement save logic (API call)
    }
  }

  // Export data
  exportData() {
    console.log('Exporting data...');
    // TODO: Implement export functionality (CSV, JSON, etc.)
    
    // Simple JSON download example
    const dataStr = JSON.stringify(this.formattedEntities, null, 2);
    const dataBlob = new Blob([dataStr], { type: 'application/json' });
    const url = URL.createObjectURL(dataBlob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `ric-o-export-${new Date().toISOString().split('T')[0]}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  // Get icon for entity type
  getIconPath(iconName: string): string {
    const icons: { [key: string]: string } = {
      'calendar': 'M8 2v3m8-3v3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z',
      'user': 'M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z',
      'file-text': 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
      'copy': 'M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z',
      'users': 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z',
      'map-pin': 'M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z M15 11a3 3 0 11-6 0 3 3 0 016 0z',
      'archive': 'M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4'
    };
    return icons[iconName] || icons['file-text'];
  }

  // Set active view
  setView(view: 'tableau' | 'graphe' | 'sources' | 'sparql') {
    this.activeView = view;
  }


}