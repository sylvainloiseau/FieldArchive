import { Component, OnInit, Input, Output, EventEmitter } from '@angular/core';
import { Entity } from '../../models/ressource';
import { CommonModule } from '@angular/common';
import { CreateRessourceComponent } from '../create-ressource/create-ressource.component';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';

@Component({
  selector: 'app-liste-entites',
  imports: [CommonModule,MatDialogModule],
  templateUrl: './liste-entites.component.html',
  styleUrl: './liste-entites.component.scss'
})
export class ListeEntitesComponent {

  // selectedType: string | null = '';
  @Input() allEntities:  any [] = [];
  @Input() selectedType: string | null = '';
  @Output() selectedEntity = new EventEmitter<any>();


  // selectedEntity: Entity | null = null;
  searchQuery: string = '';
  typeSearchQuery: string = '';

  // Sorting
  sortColumn: 'titre' | 'date' | 'source' | null = null;
  sortAscending: boolean = true;

  constructor(private dialog: MatDialog) {}

  openCreateRessourceDialog() {
    if (!this.selectedType) return;

    const [ontology, type] = this.selectedType.split(':');

    this.dialog.open(CreateRessourceComponent, {
      width: '600px',
      data: {
        ontology,
        type
      }
    });
  }

  clearTypeFilter() {
    this.selectedType = null;
    this.applyAllFilters();
  }

  // Clear all filters
  clearAllFilters() {
    // this.selectedType = null;
    // this.searchQuery = '';
    // this.typeSearchQuery = '';
    // this.sortColumn = null;
    // this.entityList = [...this.entityList];
    // this.applyAllFilters();
  }

  // TrackBy function for performance
  trackByEntityId(index: number, entity: Entity): string {
    return entity.id;
  }

  // Select entity
  selectEntity(entity: any) {
    this.selectedEntity.emit(entity);
  }


  // Filter by entity type
  filterByType(type: Entity['type']) {
    if (this.selectedType === type) {
      this.selectedType = null;
      this.applyAllFilters();
    } else {
      this.selectedType = type;
      this.applyAllFilters();
    }
  }

  // Search in entity types
  onTypeSearch() {
    // const query = this.typeSearchQuery.toLowerCase().trim();
    // if (query) {
    //   this.entityList = this.entityList.filter(et =>
    //     et.name.toLowerCase().includes(query)
    //   );
    // } else {
    //   this.entityList = [...this.entityList];
    // }
  }

  // Search functionality
  onSearch() {
    this.applyAllFilters();
  }

  // Clear search
  clearSearch() {
    this.searchQuery = '';
    this.applyAllFilters();
  }

  applyAllFilters() {
 
  }

  // Sort entities
  sortEntities(entities: Entity[], column: 'titre' | 'date' | 'source'): Entity[] {
    return entities.sort((a, b) => {
      let compareA = a[column].toLowerCase();
      let compareB = b[column].toLowerCase();

      if (column === 'date') {
        // Sort dates chronologically
        compareA = new Date(a.date).getTime().toString();
        compareB = new Date(b.date).getTime().toString();
      }

      if (this.sortAscending) {
        return compareA > compareB ? 1 : -1;
      } else {
        return compareA < compareB ? 1 : -1;
      }
    });
  }

  // Sort by column
  sortBy(column: 'titre' | 'date' | 'source') {
    if (this.sortColumn === column) {
      this.sortAscending = !this.sortAscending;
    } else {
      this.sortColumn = column;
      this.sortAscending = true;
    }
    this.applyAllFilters();
  }

  // Toggle sort order
  toggleSortOrder() {
    this.sortAscending = !this.sortAscending;
    if (this.sortColumn) {
      this.applyAllFilters();
    }
  }
}