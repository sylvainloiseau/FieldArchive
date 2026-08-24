// src/app/services/data-source-http.service.ts

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, map, tap, switchMap } from 'rxjs/operators';
import { DataSource, CreateInternalDataSourceRequest, CreateExternalDataSourceRequest } from '../models/data-source.model';

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


  // createExternalSource(data: CreateExternalDataSourceRequest): Observable<DataSource> {
  //   console.log('📡 POST /datasources/external', data);

  //   const payload = {
  //     shortName: data.shortName,
  //     name: data.name,
  //     description: data.description || '',
  //     sourceTool: data.tool || '',
  //     sourceLocation: data.url || ''
  //   };

  //   return this.http.post<any>(`${this.apiUrl}/external`, payload).pipe(
  //     map(source => this.mapSingleBackendToFrontend(source)),
  //     tap(created => {
  //       console.log('✅ External source created:', created);
  //       this.refreshDataSources();
  //     }),
  //     catchError(this.handleError)
  //   );
  // }

  /**
  * POST /datasources/external - Crée une source externe avec son fichier
  */
  createExternalSource(data: CreateExternalDataSourceRequest): Observable<DataSource> {
    console.log('📡 POST /datasources/external', data);

    const formData = new FormData();

    // Datasource metadata
    formData.append(
      'dataSource',
      new Blob(
        [JSON.stringify(data.dataSource)],
        { type: 'application/json' }
      )
    );

    // File to import
    formData.append('file', data.file, data.file.name);

    return this.http.post<any>(
      `${this.apiUrl}/external`,
      formData
    ).pipe(
      map(source => this.mapSingleBackendToFrontend(source)),
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
  updateDataSource(shortName: string, data: Partial<DataSource>): Observable<DataSource> {
    console.log(`📡 PUT /datasources/${shortName}`, data);

    const payload = {
      //name: data.name,
      longName: data.name,
      description: data.description
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
   */
  syncExternalSource(shortName: string): Observable<void> {
    console.log(`📡 POST /datasources/${shortName}/sync`);

    return this.http.post<void>(`${this.apiUrl}/${shortName}/sync`, {}).pipe(
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
      tool: source.sourceTool || source.tool,
      lastSyncDate: source.lastSync || source.lastSyncDate ? 
        new Date(source.lastSync || source.lastSyncDate) : undefined,
      previousImportDates: source.previousSyncs ? 
        source.previousSyncs.map((d: string) => new Date(d)) : [],
      createdDate: source.createdAt || source.created || source.createdDate ? 
        new Date(source.createdAt || source.created || source.createdDate) : undefined,
      recordCount: source.recordCount
    };
    
    console.log('✅ Mapped to frontend:', mapped);
    
    return mapped;
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
    } else {
      // Erreur côté serveur
      errorMessage = `Erreur ${error.status}: ${error.message}`;

      if (error.error && typeof error.error === 'string') {
        errorMessage += ` - ${error.error}`;
      }
    }

    console.error('❌ HTTP Error:', errorMessage, error);
    return throwError(() => new Error(errorMessage));
  }
}
