// src/app/models/project.model.ts

/**
 * DTO retourné par le backend pour un projet RDF
 */
export interface ProjectDto {
  /** true si un projet est actuellement ouvert */
  isOpen: boolean;
  /** Nom unique du projet (null si open=false) */
  name: string | null;
  /** Description optionnelle */
  description: string | null;
  /** Date de création (format ISO) */
  created: string | null;
  /** Date de dernière modification (format ISO) */
  lastModified: string | null;

  prefix : string;
}

/**
 * Corps de la requête POST /api/project/open
 */
export interface OpenProjectRequest {
  name: string;
  persistent?: boolean;
  description?: string;
  prefix : string;
}

/**
 * Représentation enrichie côté front (pour l'affichage en liste)
 * On l'enrichit avec un statut local et des métadonnées de tri
 */
export interface ProjectDisplay extends ProjectDto {
  /** Durée depuis la dernière modification (calculée localement) */
  lastModifiedLabel?: string;
  /** Indique si ce projet est le projet actif */
  isActive?: boolean;
}