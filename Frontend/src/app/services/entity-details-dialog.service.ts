import { Injectable } from '@angular/core';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { Observable, map } from 'rxjs';
import { EntityDetailsComponent } from '../components/entity-details/entity-details.component';
import { GestionRessourcesService } from './gestion-ressources.service';

/**
 * Opens the entity editor dialog. Shared by the entities page, the question dialog and the
 * SPARQL page, so the size and data options live in one place.
 */
@Injectable({
  providedIn: 'root'
})
export class EntityDetailsDialogService {

  constructor(private dialog: MatDialog, private gestionService: GestionRessourcesService) {}

  open(ontologyLabels: any, entityIri: string): MatDialogRef<EntityDetailsComponent> {
    return this.dialog.open(EntityDetailsComponent, {
      width: '95vw',
      maxWidth: '100vw',
      maxHeight: '80vh',
      height: '80vh',
      data: {
        "ontologyLabels": ontologyLabels,
        "selectedEntityId": entityIri
      }
    });
  }

  /** Loads the ontology payload first, for callers that don't have it (outside the entities page). */
  openLoadingTypes(entityIri: string): Observable<MatDialogRef<EntityDetailsComponent>> {
    return this.gestionService.getTypes().pipe(map(labels => this.open(labels, entityIri)));
  }
}
