import { Component, NgZone, ChangeDetectorRef, ChangeDetectionStrategy, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import { NlHistoryContext, NlQueryService } from '../../services/nl-query.service';
import { EntityDetailsDialogService } from '../../services/entity-details-dialog.service';

@Component({
  selector: 'app-sparql',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './sparql.component.html',
  changeDetection: ChangeDetectionStrategy.Default
})
export class SparqlComponent implements OnInit, OnDestroy {

  query = '';
  /** Set while the editor holds a query that comes from a natural-language question; cleared after its first successful run. */
  nlContext: NlHistoryContext | null = null;
  private pendingSub?: Subscription;
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
    private nlQueryService: NlQueryService,
    private entityDialog: EntityDetailsDialogService,
    private ngZone: NgZone,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    // A query handed over by the question dialog (also when this page is already open).
    this.pendingSub = this.nlQueryService.pendingSparql$.subscribe(pending => {
      if (!pending) return;
      this.nlQueryService.pendingSparql$.next(null);
      this.query = pending.query;
      this.selectedType = pending.type;
      this.nlContext = pending.nl;
      this.clearResults();
      this.cdr.detectChanges();
      if (pending.autoRun) this.runQuery();
    });
  }

  ngOnDestroy(): void {
    this.pendingSub?.unsubscribe();
  }

  loadExample(ex: { label: string; type: 'SELECT' | 'UPDATE'; query: string }): void {
    this.query = ex.query;
    this.selectedType = ex.type;
    this.nlContext = null;
    this.clearResults();
  }

  clearAll(): void {
    this.query = '';
    this.nlContext = null;
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

    // Line breaks are kept: they end "#" comments, so collapsing them would comment out the rest of the query.
    const normalizedQuery = this.query
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\')
      .trim();
    const nl = this.nlContext;

    this.loading = true;
    this.error = null;
    this.updateSuccess = false;
    this.results = [];
    this.columns = [];
    this.hasRun = true;
    this.cdr.detectChanges();

    if (this.selectedType === 'SELECT') {
      this.gestionService.runSelectQuery(normalizedQuery, nl).pipe(
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
            if (nl) this.nlContext = null; // recorded once; later manual runs are not
            this.cdr.detectChanges();
          });
        },
        error: (err: any) => {
          this.ngZone.run(() => {
            this.error = this.errorText(err);
            this.cdr.detectChanges();
          });
        }
      });
    } else {
      this.gestionService.runUpdateQuery(normalizedQuery, nl).pipe(
        finalize(() => {
          this.ngZone.run(() => {
            this.loading = false;
            this.cdr.detectChanges();
          });
        })
      ).subscribe({
        next: () => {
          this.ngZone.run(() => {
            this.updateSuccess = true;
            this.gestionService.entitiesChanged$.next();
            if (nl) {
              this.nlContext = null;
              // An update decided from a question ends like the dialog's "Apply changes": open the entity it targets.
              if (nl.targetEntityIri) this.openEntityIfExists(nl.targetEntityIri);
            }
            this.cdr.detectChanges();
          });
        },
        error: (err: any) => {
          this.ngZone.run(() => {
            this.error = this.errorText(err);
            this.cdr.detectChanges();
          });
        }
      });
    }
  }

  private errorText(err: any): string {
    return err?.error?.error ?? err?.error?.message ?? err?.message ?? 'An error occurred while executing the query.';
  }

  private openEntityIfExists(iri: string): void {
    this.gestionService.getEntityDetails(iri).subscribe({
      next: () => this.entityDialog.openLoadingTypes(iri).subscribe(),
      error: () => {} // deleted by the update: nothing to open
    });
  }
}