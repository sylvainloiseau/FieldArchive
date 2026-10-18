// src/app/services/data-source-http.service.ts

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, map, tap, switchMap } from 'rxjs/operators';
import { DataSource, CreateInternalDataSourceRequest, CreateExternalDataSourceRequest, ImporterSettings } from '../models/data-source.model';
import { ImportResult, RDF_FILE_IMPORTER } from '../models/importer.model';

@Injectable({
  providedIn: 'root'
})
export class DataSourceHttpService {
  private apiUrl = 'http://localhost:8080/datasources';

  private dataSourcesSubject = new BehaviorSubject<DataSource[]>([]);
  public dataSources$ = this.dataSourcesSubject.asObservable();

  constructor(private http: HttpClient) {
    console.log('🌐 DataSourceHttpService initialized');
  }

  /**
   * GET /datasources - Liste toutes les sources
   */
  getAllDataSources(): Observable<DataSource[]> {
    console.log('📡 GET /datasources');

    return this.http.get<any[]>(this.apiUrl).pipe(
      map(sources => this.mapBackendToFrontend(sources)),
      tap(sources => {
        console.log('✅ Received sources:', sources);
        this.dataSourcesSubject.next(sources);
      }),
      catchError(this.handleError)
    );
  }

  /**
   * POST /datasources/internal - Crée une source interne
   */
  createInternalSource(data: CreateInternalDataSourceRequest): Observable<DataSource> {
    console.log('📡 POST /datasources/internal', data);
    
    const payload = {
      shortName: data.shortName,
      name: data.name,
      description: data.description || ''
    };

    return this.http.post<any>(`${this.apiUrl}/internal`, payload).pipe(
      // Après création, récupère la source complète
      switchMap(() => this.http.get<any>(`${this.apiUrl}/${data.shortName}`)),
      map(source => this.mapSingleBackendToFrontend(source)),
      tap(created => {
        console.log('✅ Internal source created:', created);
        this.refreshDataSources();
      }),
      catchError(this.handleError)
    );
  }


  /**
  * POST /datasources/external - Crée une source externe et l'importe : le fichier envoyé, sinon
  * le chemin (un fichier RDF, ou le dossier lu par un plugin). Answers the import result, or null
  * when there was nothing to import.
  */
  createExternalSource(data: CreateExternalDataSourceRequest): Observable<ImportResult | null> {
    console.log('📡 POST /datasources/external', data);

    const formData = new FormData();

    // Map the form value onto the backend DTO: the form calls the path `url`, the
    // backend expects `sourceLocation`, and Jackson silently drops unknown names.
    const payload = {
      shortName: data.dataSource.shortName,
      name: data.dataSource.name,
      description: data.dataSource.description || '',
      sourceLocation: data.dataSource.url || '',
      ...data.settings
    };

    // Datasource metadata
    formData.append(
      'dataSource',
      new Blob(
        [JSON.stringify(payload)],
        { type: 'application/json' }
      )
    );

    // File to import, if any
    if (data.file) {
      formData.append('file', data.file, data.file.name);
    }

    return this.http.post<any>(
      `${this.apiUrl}/external`,
      formData
    ).pipe(
      map(body => (body?.import ?? null) as ImportResult | null),
      tap(created => {
        console.log('✅ External source created:', created);
        this.refreshDataSources();
      }),
      catchError(this.handleError)
    );
  }

  /**
   * PUT /datasources/{shortName} - Modifie les métadonnées
   */
  updateDataSource(shortName: string, data: Partial<DataSource>, settings?: ImporterSettings): Observable<DataSource> {
    console.log(`📡 PUT /datasources/${shortName}`, data);

    const payload = {
      //name: data.name,
      longName: data.name,
      description: data.description,
      sourceLocation: data.url,
      ...(settings ?? {})
    };
    //Debug
    console.log('Payload envoyé:', payload);  

    return this.http.put<any>(`${this.apiUrl}/${shortName}`, payload).pipe(
      map(source => this.mapSingleBackendToFrontend(source)),
      tap(updated => {
        console.log('✅ Source updated:', updated);
        this.refreshDataSources();
      }),
      catchError(this.handleError)
    );
  }

  /**
   * DELETE /datasources/{shortName} - Supprime la source
   */
  deleteDataSource(shortName: string): Observable<void> {
    console.log(`📡 DELETE /datasources/${shortName}`);

    return this.http.delete<void>(`${this.apiUrl}/${shortName}`).pipe(
      tap(() => {
        console.log('✅ Source deleted');
        this.refreshDataSources();
      }),
      catchError(this.handleError)
    );
  }

  /**
   * POST /datasources/{shortName}/sync - Réimporte depuis le fichier
   *
   * Without `file`, the backend re-reads the path it recorded for the source. With one,
   * it imports the uploaded bytes instead — which also works when no path is known, or
   * when the backend runs on another machine.
   */
  syncExternalSource(shortName: string, file?: File, sourceLocation?: string,
                     settings?: ImporterSettings): Observable<ImportResult> {
    console.log(`📡 POST /datasources/${shortName}/sync`, file?.name ?? '(no file)');

    // With a file: its bytes (and its path, to remember). Without: the path for the backend to
    // read, or nothing to re-read the recorded one. The importer settings, when given, are the
    // form's (recorded by the backend once the import succeeds).
    const fields: Record<string, string> = {};
    if (sourceLocation) fields['sourceLocation'] = sourceLocation;
    if (settings) {
      fields['importer'] = settings.importer;
      if (settings.importerOptions) fields['importerOptions'] = JSON.stringify(settings.importerOptions);
      if (settings.importBaseIri != null) fields['importBaseIri'] = settings.importBaseIri;
    }

    let body: FormData | {} = {};
    let params = new HttpParams();
    if (file) {
      const formData = new FormData();
      formData.append('file', file, file.name);
      Object.entries(fields).forEach(([k, v]) => formData.append(k, v));
      body = formData;
    } else {
      Object.entries(fields).forEach(([k, v]) => params = params.set(k, v));
    }

    return this.http.post<ImportResult>(`${this.apiUrl}/${shortName}/sync`, body, { params }).pipe(
      tap(() => {
        console.log('✅ Source synchronized');
        this.refreshDataSources();
      }),
      catchError(this.handleError)
    );
  }

  /**
   * Mapping Backend → Frontend
   */
  private mapBackendToFrontend(sources: any[]): DataSource[] {
    return sources.map(s => this.mapSingleBackendToFrontend(s));
  }

  private mapSingleBackendToFrontend(source: any): DataSource {
    console.log('🔄 Mapping source from backend:', source);
    
    if (!source) {
      console.error('❌ Source is null or undefined');
      return {
        id: '', shortName: '', name: '', description: '',
        sourceType: 'INTERNAL', graphIRI: '', editable: false
      } as DataSource;
    }
    
    // Normalise le sourceType
    const rawType = source.type || source.sourceType || source.datasourceType || 'INTERNAL';
    const sourceType = rawType.toString().toUpperCase();
    
    const mapped: DataSource = {
      id: source.shortName || source.id || '',
      shortName: source.shortName || '',
      name: source.longName || source.name || source.shortName || '',  // ✅ longName en premier !
      description: source.description || '',
      sourceType: sourceType as 'INTERNAL' | 'EXTERNAL',
      graphIRI: source.graphIri || source.graphIRI || source.graphUri || `urn:datasource:${source.shortName}`,
      editable: source.editable !== false,
      url: source.sourceLocation || source.location || source.url,
      tool: this.importerLabel(source),
      importer: source.importer,
      importerStatus: source.importerStatus,
      importerOptions: source.importerOptions || {},
      importBaseIri: source.importBaseIri,
      importBaseIriDefault: source.importBaseIriDefault,
      lastImport: source.lastImport,
      lastSyncDate: source.lastSync || source.lastSyncDate ? 
        new Date(source.lastSync || source.lastSyncDate) : undefined,
      previousImportDates: source.previousSyncs ? 
        source.previousSyncs.map((d: string) => new Date(d)) : [],
      createdDate: source.createdAt || source.created || source.createdDate ? 
        new Date(source.createdAt || source.created || source.createdDate) : undefined,
      recordCount: source.recordCount,
      tripleCount: source.tripleCount
    };
    
    console.log('✅ Mapped to frontend:', mapped);
    
    return mapped;
  }

  /** What the "Source" column shows: the plugin name, "RDF file", or the stored value without plugin. */
  private importerLabel(source: any): string | undefined {
    if (!source.importer) return source.sourceTool || undefined;
    return source.importer === RDF_FILE_IMPORTER ? 'RDF file' : source.importer;
  }

  /**
   * Rafraîchir la liste après modification
   */
  private refreshDataSources(): void {
    this.getAllDataSources().subscribe();
  }

  /**
   * Gestion des erreurs HTTP
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Une erreur est survenue';

    if (error.error instanceof ErrorEvent) {
      // Erreur côté client
      errorMessage = `Erreur: ${error.error.message}`;
    } else if (error.error && typeof error.error === 'object' && error.error.error) {
      // ApiExceptionHandler answers {"error": "..."} — that message is the useful one,
      // and callers branch on it (e.g. to offer a file picker when no path is recorded).
      errorMessage = error.error.error;
    } else {
      // Erreur côté serveur
      errorMessage = `Erreur ${error.status}: ${error.message}`;

      if (error.error && typeof error.error === 'string') {
        errorMessage += ` - ${error.error}`;
      }
    }

    console.error('❌ HTTP Error:', errorMessage, error);
    const wrapped = new Error(errorMessage) as Error & { status?: number };
    wrapped.status = error.status;
    return throwError(() => wrapped);
  }
}
