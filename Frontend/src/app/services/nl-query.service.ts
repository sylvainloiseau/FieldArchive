import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';

export interface NlClarification {
  kind: 'ENTITY' | 'ENCODING';
  entityIri?: string | null;
  optionId?: string | null;
  statement: string;
}

export interface NlHistoryContext {
  question: string;
  clarifications: NlClarification[];
  generatedSparql: string;
  targetEntityIri: string | null;
  createdEntityIris: string[];
  provider: string;
  model: string;
}

export interface EntityMatch {
  iri: string;
  label: string;
  types: string[];
  source: 'internal' | 'external';
  datasourceShortName: string | null;
}

export interface EncodingOption {
  id: string;
  label: string;
  description: string;
  basis: string;
  exampleTriple: string;
  statement: string;
}

export interface NlQueryAnswer {
  queryType: 'SELECT' | 'UPDATE' | 'CLARIFY' | 'UNSUPPORTED';
  sparql: string | null;
  explanation: string;
  targetEntityIri: string | null;
  createdEntityIris: string[];
  clarifyKind: 'ENTITY' | 'ENCODING' | null;
  candidates: EntityMatch[];
  encodingOptions: EncodingOption[];
  lookups: string[];
  examples: string[];
  provider: string;
  model: string;
}

export interface ProviderStatus {
  id: string;
  label: string;
  model: string;
  available: boolean;
  free: boolean;
  keySource: 'environment' | 'settings' | null;
  reason: string | null;
}

export interface NlHistoryEntry {
  id: string;
  timestamp: string;
  question: string;
  clarifications: NlClarification[];
  queryType: string;
  sparql: string;
  edited: boolean;
  provider: string;
  model: string;
  useCount: number;
}

/** Hand-off from the question dialog to the SPARQL page. */
export interface PendingSparql {
  query: string;
  type: 'SELECT' | 'UPDATE';
  autoRun: boolean;
  nl: NlHistoryContext;
}

@Injectable({
  providedIn: 'root'
})
export class NlQueryService {

  private apiUrl = 'http://localhost:8080/nlquery';

  /**
   * A subject rather than a plain field: navigating to /sparql while already on /sparql does
   * not re-create the page, so it must be able to receive a new query at any time.
   * The consumer sets it back to null after reading it.
   */
  readonly pendingSparql$ = new BehaviorSubject<PendingSparql | null>(null);

  constructor(private http: HttpClient) {}

  status(): Observable<{ providers: ProviderStatus[] }> {
    return this.http.get<{ providers: ProviderStatus[] }>(`${this.apiUrl}/status`);
  }

  translate(requestId: string, provider: string, question: string, clarifications: NlClarification[]): Observable<NlQueryAnswer> {
    return this.http.post<NlQueryAnswer>(`${this.apiUrl}/translate`, { requestId, provider, question, clarifications });
  }

  cancel(requestId: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/cancel/${encodeURIComponent(requestId)}`, {});
  }

  saveKey(provider: string, apiKey: string): Observable<void> {
    return this.http.put<void>(`${this.apiUrl}/keys`, { provider, apiKey });
  }

  deleteKey(provider: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/keys/${provider}`);
  }

  history(): Observable<NlHistoryEntry[]> {
    return this.http.get<NlHistoryEntry[]>(`${this.apiUrl}/history`);
  }

  deleteHistoryEntry(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/history/${encodeURIComponent(id)}`);
  }

  /** The last agent used, remembered in this browser only. */
  lastProvider(): string | null {
    try {
      return localStorage.getItem('fieldarchive.nlquery.provider');
    } catch {
      return null;
    }
  }

  rememberProvider(id: string): void {
    try {
      localStorage.setItem('fieldarchive.nlquery.provider', id);
    } catch {
      // private window or storage blocked: not remembering is fine
    }
  }
}
