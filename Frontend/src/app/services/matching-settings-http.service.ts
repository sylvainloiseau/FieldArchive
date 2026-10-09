import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  GroupCoverage, MatchingConfiguration, MatchingConfigurationEnvelope, MatchingPreview, PredicateCandidate,
  TypeCandidate, TypeGroup, ValidationReport
} from '../models/matching.model';

/** The /matching/configuration endpoints, used by the matching settings page. */
@Injectable({
  providedIn: 'root'
})
export class MatchingSettingsHttpService {

  private readonly baseUrl = 'http://localhost:8080/matching/configuration';

  constructor(private http: HttpClient) {}

  get(): Observable<MatchingConfigurationEnvelope> {
    return this.http.get<MatchingConfigurationEnvelope>(this.baseUrl);
  }

  getDefault(): Observable<MatchingConfigurationEnvelope> {
    return this.http.get<MatchingConfigurationEnvelope>(`${this.baseUrl}/default`);
  }

  save(configuration: MatchingConfiguration): Observable<MatchingConfigurationEnvelope> {
    return this.http.put<MatchingConfigurationEnvelope>(this.baseUrl, configuration);
  }

  reset(): Observable<MatchingConfigurationEnvelope> {
    return this.http.delete<MatchingConfigurationEnvelope>(this.baseUrl);
  }

  validate(configuration: MatchingConfiguration): Observable<ValidationReport> {
    return this.http.post<ValidationReport>(`${this.baseUrl}/validate`, configuration);
  }

  types(): Observable<TypeCandidate[]> {
    return this.http.get<TypeCandidate[]>(`${this.baseUrl}/vocabulary/types`);
  }

  predicates(types: string[]): Observable<PredicateCandidate[]> {
    let params = new HttpParams();
    types.forEach(t => params = params.append('types', t));
    return this.http.get<PredicateCandidate[]>(`${this.baseUrl}/vocabulary/predicates`, { params });
  }

  coverage(group: TypeGroup, prefixes: Record<string, string>, includeSubclasses: boolean): Observable<GroupCoverage> {
    return this.http.post<GroupCoverage>(`${this.baseUrl}/coverage`, { group, prefixes, includeSubclasses });
  }

  preview(configuration: MatchingConfiguration, dataSourceA: string, dataSourceB: string): Observable<MatchingPreview> {
    return this.http.post<MatchingPreview>(`${this.baseUrl}/preview`, { configuration, dataSourceA, dataSourceB });
  }

  exportFile(): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/export`, { responseType: 'blob' });
  }

  importFile(file: File): Observable<MatchingConfigurationEnvelope> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<MatchingConfigurationEnvelope>(`${this.baseUrl}/import`, form);
  }
}
