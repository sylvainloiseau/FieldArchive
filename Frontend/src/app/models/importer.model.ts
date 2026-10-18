// src/app/models/importer.model.ts — importers of external data sources (GET /importers)

/** Id of the built-in importer "Sync with an RDF file". */
export const RDF_FILE_IMPORTER = 'rdf-file';

export type InputKind = 'FILE' | 'DIRECTORY' | 'FILE_OR_DIRECTORY';

export interface ImporterOption {
  name: string;
  description: string;
  defaultValue: string | null;
}

/** "Sync with an RDF file" (origin BUILT_IN) or a plugin of a ricImporterPlugins directory. */
export interface Importer {
  id: string;
  label: string;
  description: string;
  options: ImporterOption[];
  inputKind: InputKind;
  origin: 'BUILT_IN' | 'BUNDLED' | 'USER';
  version: string | null;
  location: string | null;
}

export interface PluginProblem {
  location: string;
  message: string;
  /** false for a note (a bundled plugin replaced by the user's one) */
  error: boolean;
}

export interface Importers {
  /** The plugins by label, then "Sync with an RDF file" last. */
  importers: Importer[];
  problems: PluginProblem[];
  directories: { bundled: string | null; user: string | null };
}

/** How the stored importer of an external source resolves. */
export type ImporterStatus = 'RDF_FILE' | 'PLUGIN' | 'MISSING';

/** The last successful import of an external source. */
export interface LastImport {
  importer: string;
  importerVersion: string | null;
  statements: number;
  date: string;
  input: string;
  warnings: string[];
}

/** Answer of an import (sync, or creation of an external source). */
export interface ImportResult {
  sourceName: string;
  tripleCount: number;
  importer: string;
  warnings: string[];
}
