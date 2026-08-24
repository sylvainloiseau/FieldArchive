// src/app/models/data-source.model.ts

export interface DataSource {
  id?: string;
  shortName: string; // Used as the named graph identifier
  name: string;
  description: string;
  sourceType: 'INTERNAL' | 'EXTERNAL';
  createdDate?: Date;
  lastSyncDate?: Date;
  previousImportDates?: Date[]; // NEW: Track import history
  url?: string; // For external sources
  tool?: string; // For external sources (e.g., Tropy, Lameta, Gramps)
  graphIRI?: string; // Named graph identifier (e.g., urn:datasource:shortName)
  editable?: boolean; // NEW: Is this data source editable?
  recordCount?: number; // NEW: Number of records in this source
}

export interface CreateInternalDataSourceRequest {
  shortName: string;
  name: string;
  description: string;
  sourceType: 'INTERNAL' | 'EXTERNAL';
  url?: string;
  tool?: string;

  filePath?: string;
  delimiter?: string;
  subjectColumn?: string;
}

export interface ImportRequest {
  dataSourceId: string;
  rdfFileUrl: string; // Path to RDF file to import
}

// NEW: Statistics about a data source
export interface DataSourceStats {
  recordCount: number;
  eventCount: number;
  personCount: number;
  lastModified: Date;
}

export interface CreateExternalDataSourceRequest {
  dataSource: CreateInternalDataSourceRequest;
  file: File;
}