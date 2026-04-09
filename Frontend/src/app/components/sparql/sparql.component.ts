import { Component, NgZone, ChangeDetectorRef, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';

@Component({
  selector: 'app-sparql',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './sparql.component.html',
  changeDetection: ChangeDetectionStrategy.Default
})
export class SparqlComponent {

  query = '';
  results: any[] = [];
  columns: string[] = [];
  error: string | null = null;
  loading = false;
  hasRun = false;
  updateSuccess = false;
  selectedType: 'SELECT' | 'UPDATE' = 'SELECT';
  viewMode: 'table' | 'json' = 'table';

  queryTypes: Array<'SELECT' | 'UPDATE'> = ['SELECT', 'UPDATE'];

  activeTabClass = 'px-3 py-1 text-xs rounded-lg bg-white dark:bg-gray-700 border border-gray-200 dark:border-gray-600 text-gray-800 dark:text-gray-100 font-medium shadow-sm';
  inactiveTabClass = 'px-3 py-1 text-xs rounded-lg text-gray-500 dark:text-gray-400 hover:bg-white dark:hover:bg-gray-700 transition-colors';

  examples = [
    {
      label: 'All classes',
      type: 'SELECT' as const,
      query: `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?class ?label WHERE {
  ?class a owl:Class .
  OPTIONAL { ?class rdfs:label ?label . }
} LIMIT 20`
    },
    {
      label: 'All triples',
      type: 'SELECT' as const,
      query: `SELECT ?s ?p ?o WHERE {
  ?s ?p ?o .
} LIMIT 50`
    },
    {
      label: 'Named graphs',
      type: 'SELECT' as const,
      query: `SELECT DISTINCT ?g WHERE {
  GRAPH ?g { ?s ?p ?o }
}`
    }
  ];

  constructor(
    private gestionService: GestionRessourcesService,
    private ngZone: NgZone,
    private cdr: ChangeDetectorRef
  ) {}

  loadExample(ex: { label: string; type: 'SELECT' | 'UPDATE'; query: string }): void {
    this.query = ex.query;
    this.selectedType = ex.type;
    this.clearResults();
  }

  clearAll(): void {
    this.query = '';
    this.clearResults();
  }

  private clearResults(): void {
    this.results = [];
    this.columns = [];
    this.error = null;
    this.updateSuccess = false;
    this.hasRun = false;
  }

  runQuery(): void {
    if (!this.query.trim()) return;

    const normalizedQuery = this.query
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\')
      .replace(/\s+/g, ' ')
      .trim();

    this.loading = true;
    this.error = null;
    this.updateSuccess = false;
    this.results = [];
    this.columns = [];
    this.hasRun = true;
    this.cdr.detectChanges();

    if (this.selectedType === 'SELECT') {
      this.gestionService.runSelectQuery(normalizedQuery).pipe(
        finalize(() => {
          this.ngZone.run(() => {
            this.loading = false;
            this.cdr.detectChanges();
          });
        })
      ).subscribe({
        next: (data: any[]) => {
          this.ngZone.run(() => {
            this.results = data ?? [];
            if (this.results.length > 0) {
              this.columns = Object.keys(this.results[0]);
            }
            this.cdr.detectChanges();
          });
        },
        error: (err: any) => {
          this.ngZone.run(() => {
            this.error = err?.error?.message ?? err?.message ?? 'An error occurred while executing the query.';
            this.cdr.detectChanges();
          });
        }
      });
    } 
  }
}