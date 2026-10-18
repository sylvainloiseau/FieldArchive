// src/app/services/importer-http.service.ts

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { Importers } from '../models/importer.model';

/** The importers of external data sources: the RDF file importer and the plugins found by the backend. */
@Injectable({ providedIn: 'root' })
export class ImporterHttpService {
  private apiUrl = 'http://localhost:8080/importers';

  private importersSubject = new BehaviorSubject<Importers | null>(null);
  public importers$ = this.importersSubject.asObservable();

  constructor(private http: HttpClient) {}

  load(): Observable<Importers> {
    return this.http.get<Importers>(this.apiUrl).pipe(tap(i => this.importersSubject.next(i)));
  }

  /** Scans the plugin directories again (a plugin was added or removed). */
  rescan(): Observable<Importers> {
    return this.http.post<Importers>(`${this.apiUrl}/rescan`, {}).pipe(tap(i => this.importersSubject.next(i)));
  }
}
