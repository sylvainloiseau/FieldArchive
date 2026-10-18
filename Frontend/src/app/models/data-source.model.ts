// src/app/models/data-source.model.ts

import { ImporterStatus, LastImport } from './importer.model';

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
  tripleCount?: number; // Number of RDF triples in the source's named graph
  // External sources: how they are imported
  importer?: string; // a plugin name or 'rdf-file'; the stored value when no plugin has it
  importerStatus?: ImporterStatus;
  importerOptions?: Record<string, string>;
  importBaseIri?: string | null; // prefix of the IRIs minted by a plugin
  importBaseIriDefault?: string;
  lastImport?: LastImport | null;
}

/** What the form sends about the importer of an external source. */
export interface ImporterSettings {
  importer: string;
  /** Plugins only; omitted for an RDF file (the stored options are then kept). */
  importerOptions?: Record<string, string>;
  /** Plugins only; '' = the default base IRI. Omitted for an RDF file (the stored one is kept). */
  importBaseIri?: string;
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
  /** The RDF file to upload; without it the backend reads dataSource.url (a file, or a plugin's directory). */
  file?: File | null;
  settings: ImporterSettings;
}