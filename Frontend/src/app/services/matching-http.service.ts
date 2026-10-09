import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  LinkAction, LinkChangeResult, MatchableEntity, MatchCluster, MatchingOverview, MatchingRunSummary,
  MatchLinkPage, MatchStatus, PairComparison
} from '../models/matching.model';

export interface LinkQuery {
  dataSourceA: string;
  dataSourceB: string;
  status?: MatchStatus[];
  typeGroupId?: string | null;
  minScore?: number | null;
  q?: string | null;
  includeSuperseded?: boolean;
  includeOrphaned?: boolean;
  offset?: number;
  limit?: number;
}

/** The /matching endpoints: runs, review of the links, manual links, comparison, clusters. */
@Injectable({
  providedIn: 'root'
})
export class MatchingHttpService {

  private readonly baseUrl = 'http://localhost:8080/matching';

  constructor(private http: HttpClient) {}

  overview(): Observable<MatchingOverview> {
    return this.http.get<MatchingOverview>(`${this.baseUrl}/overview`);
  }

  run(dataSourceA: string, dataSourceB: string): Observable<MatchingRunSummary[]> {
    return this.http.post<MatchingRunSummary[]>(`${this.baseUrl}/runs`, { dataSourceA, dataSourceB });
  }

  runAll(): Observable<MatchingRunSummary[]> {
    return this.http.post<MatchingRunSummary[]>(`${this.baseUrl}/runs`, { all: true });
  }

  links(query: LinkQuery): Observable<MatchLinkPage> {
    let params = new HttpParams()
      .set('dataSourceA', query.dataSourceA)
      .set('dataSourceB', query.dataSourceB)
      .set('includeSuperseded', String(!!query.includeSuperseded))
      .set('includeOrphaned', String(query.includeOrphaned ?? true))
      .set('offset', String(query.offset ?? 0))
      .set('limit', String(query.limit ?? 200));
    (query.status ?? []).forEach(s => params = params.append('status', s));
    if (query.typeGroupId) params = params.set('typeGroupId', query.typeGroupId);
    if (query.minScore != null) params = params.set('minScore', String(query.minScore));
    if (query.q) params = params.set('q', query.q);
    return this.http.get<MatchLinkPage>(`${this.baseUrl}/links`, { params });
  }

  action(linkId: string, action: LinkAction, dryRun: boolean, note?: string | null): Observable<LinkChangeResult> {
    return this.http.post<LinkChangeResult>(`${this.baseUrl}/links/${encodeURIComponent(linkId)}/actions`,
      { action, note: note ?? null }, { params: { dryRun: String(dryRun) } });
  }

  bulkAction(ids: string[], action: LinkAction, dryRun: boolean): Observable<LinkChangeResult> {
    return this.http.post<LinkChangeResult>(`${this.baseUrl}/links/actions`, { ids, action },
      { params: { dryRun: String(dryRun) } });
  }

  updateNote(linkId: string, note: string | null): Observable<LinkChangeResult> {
    return this.http.put<LinkChangeResult>(`${this.baseUrl}/links/${encodeURIComponent(linkId)}/note`, { note });
  }

  createManualLink(a: { dataSource: string; iri: string }, b: { dataSource: string; iri: string },
                   dryRun: boolean, note?: string | null): Observable<LinkChangeResult> {
    return this.http.post<LinkChangeResult>(`${this.baseUrl}/links`, { a, b, note: note ?? null },
      { params: { dryRun: String(dryRun) } });
  }

  removeOrphaned(pairKey: string | null, dataSource: string | null, dryRun: boolean): Observable<LinkChangeResult> {
    let params = new HttpParams().set('dryRun', String(dryRun));
    if (pairKey) params = params.set('pair', pairKey);
    if (dataSource) params = params.set('dataSource', dataSource);
    return this.http.delete<LinkChangeResult>(`${this.baseUrl}/links/orphaned`, { params });
  }

  compare(dataSourceA: string, iriA: string, dataSourceB: string, iriB: string): Observable<PairComparison> {
    return this.http.get<PairComparison>(`${this.baseUrl}/compare`, { params: { dataSourceA, iriA, dataSourceB, iriB } });
  }

  entities(dataSource: string, q: string, typeGroupId: string | null, unmatchedWith: string | null,
           limit = 50): Observable<MatchableEntity[]> {
    let params = new HttpParams().set('dataSource', dataSource).set('limit', String(limit));
    if (q) params = params.set('q', q);
    if (typeGroupId) params = params.set('typeGroupId', typeGroupId);
    if (unmatchedWith) params = params.set('unmatchedWith', unmatchedWith);
    return this.http.get<MatchableEntity[]>(`${this.baseUrl}/entities`, { params });
  }

  clusters(minSize = 3): Observable<MatchCluster[]> {
    return this.http.get<MatchCluster[]>(`${this.baseUrl}/clusters`, { params: { minSize: String(minSize) } });
  }

  /** The message of a failed call: the backend's {"error": …} when there is one. */
  static errorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.error && typeof err.error === 'object' && err.error.error) return err.error.error;
      if (typeof err.error === 'string' && err.error) return err.error;
      if (err.status === 0) return 'The backend is not reachable.';
      return `${err.status} ${err.statusText}`;
    }
    return String(err);
  }
}
