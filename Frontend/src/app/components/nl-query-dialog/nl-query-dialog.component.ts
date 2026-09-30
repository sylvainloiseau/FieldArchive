import { Component, ChangeDetectorRef, ElementRef, Inject, OnDestroy, OnInit, Optional, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subscription } from 'rxjs';
import {
  EncodingOption, EntityMatch, NlClarification, NlHistoryContext, NlHistoryEntry, NlQueryAnswer,
  NlQueryService, ProviderStatus
} from '../../services/nl-query.service';
import { GestionRessourcesService } from '../../services/gestion-ressources.service';
import { EntityDetailsDialogService } from '../../services/entity-details-dialog.service';

export interface NlQueryDialogData {
  initialQuestion?: string;
}

/**
 * Ask a question in natural language; the selected agent (Claude, ChatGPT, a local model or the
 * built-in agent) turns it into SPARQL. Nothing runs until the user clicks Run / Apply changes.
 */
@Component({
  selector: 'app-nl-query-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './nl-query-dialog.component.html',
})
export class NlQueryDialogComponent implements OnInit, OnDestroy {

  @ViewChild('questionBox') questionBox?: ElementRef<HTMLTextAreaElement>;

  providers: ProviderStatus[] = [];
  selectedProvider = '';
  question = '';
  clarifications: NlClarification[] = [];

  answer: NlQueryAnswer | null = null;
  error: string | null = null;
  translating = false;
  running = false;
  elapsed = 0;
  private timer?: ReturnType<typeof setInterval>;
  private requestId: string | null = null;
  private translateSub?: Subscription;

  showLookups = false;
  showHistory = false;
  history: NlHistoryEntry[] = [];
  confirmDeleteId: string | null = null;

  keyEditing: string | null = null;
  keyValue = '';
  keyError: string | null = null;
  keySaving = false;
  showExamples = false;

  readonly builtinExamples = [
    'list all persons', 'liste des personnes', 'how many records', 'persons named Dupont',
    'records whose title contains chant', 'show Marie Dupont', 'create a person named Marie Dupont',
    'rename Marie Dupont to Marie Durand', 'set the description of Marie Dupont to …',
    "add the gender 'masculine' to Jean Dupond", 'delete Marie Dupont'
  ];

  constructor(
    private nlQuery: NlQueryService,
    private gestionService: GestionRessourcesService,
    private entityDialog: EntityDetailsDialogService,
    private router: Router,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private dialogRef: MatDialogRef<NlQueryDialogComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) private data: NlQueryDialogData | null
  ) {}

  ngOnInit(): void {
    if (this.data?.initialQuestion) this.question = this.data.initialQuestion;
    this.loadStatus(() => {
      // Opened from the header field: the first proposal is computed straight away.
      if (this.data?.initialQuestion && this.selectedProvider) this.translate();
    });
  }

  ngOnDestroy(): void {
    this.stopTimer();
    if (this.translating && this.requestId) this.nlQuery.cancel(this.requestId).subscribe({ error: () => {} });
    this.translateSub?.unsubscribe();
  }

  // =========================
  //  Agents and keys
  // =========================

  private loadStatus(then?: () => void): void {
    this.nlQuery.status().subscribe({
      next: s => {
        this.providers = s.providers;
        if (!this.current?.available) this.selectedProvider = this.defaultProvider();
        this.cdr.detectChanges();
        then?.();
      },
      error: () => {
        this.error = 'Cannot reach the backend.';
        this.cdr.detectChanges();
      }
    });
  }

  /** The last agent used if still available, else the first available one (Built-in always is). */
  private defaultProvider(): string {
    const last = this.nlQuery.lastProvider();
    if (last && this.providers.some(p => p.id === last && p.available)) return last;
    return this.providers.find(p => p.available)?.id ?? '';
  }

  get current(): ProviderStatus | undefined {
    return this.providers.find(p => p.id === this.selectedProvider);
  }

  get paidProviders(): ProviderStatus[] {
    return this.providers.filter(p => p.id === 'claude' || p.id === 'openai');
  }

  get noPaidAgent(): boolean {
    return this.paidProviders.every(p => !p.available);
  }

  get otherAvailable(): ProviderStatus[] {
    return this.providers.filter(p => p.available && p.id !== this.selectedProvider);
  }

  onProviderChange(id: string): void {
    this.selectedProvider = id;
    this.nlQuery.rememberProvider(id);
    this.answer = null;
    this.clarifications = [];
    this.error = null;
  }

  startKeyEdit(providerId: string): void {
    this.keyEditing = providerId;
    this.keyValue = '';
    this.keyError = null;
  }

  saveKey(): void {
    if (!this.keyEditing || !this.keyValue.trim()) return;
    const provider = this.keyEditing;
    this.keySaving = true;
    this.keyError = null;
    this.nlQuery.saveKey(provider, this.keyValue.trim()).subscribe({
      next: () => {
        this.keySaving = false;
        this.keyEditing = null;
        this.keyValue = '';
        this.loadStatus(() => {
          if (this.providers.some(p => p.id === provider && p.available)) this.onProviderChange(provider);
        });
      },
      error: err => {
        this.keySaving = false;
        this.keyError = this.errorText(err);
        this.cdr.detectChanges();
      }
    });
  }

  deleteKey(providerId: string): void {
    this.nlQuery.deleteKey(providerId).subscribe({ next: () => this.loadStatus(), error: err => this.error = this.errorText(err) });
  }

  // =========================
  //  Translation
  // =========================

  onQuestionKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.translate();
    }
  }

  translate(): void {
    if (!this.question.trim() || !this.selectedProvider || this.translating) return;
    this.error = null;
    this.answer = null;
    this.showLookups = false;
    this.translating = true;
    this.requestId = this.newRequestId();
    this.startTimer();
    this.nlQuery.rememberProvider(this.selectedProvider);
    this.translateSub = this.nlQuery.translate(this.requestId, this.selectedProvider, this.question.trim(), this.clarifications).subscribe({
      next: a => {
        this.translating = false;
        this.stopTimer();
        this.answer = a;
        this.cdr.detectChanges();
      },
      error: err => {
        this.translating = false;
        this.stopTimer();
        this.error = this.errorText(err);
        this.cdr.detectChanges();
      }
    });
  }

  cancelTranslation(): void {
    if (this.requestId) this.nlQuery.cancel(this.requestId).subscribe({ error: () => {} });
    this.translateSub?.unsubscribe();
    this.translating = false;
    this.stopTimer();
  }

  pickCandidate(c: EntityMatch): void {
    this.clarifications = [...this.clarifications,
      { kind: 'ENTITY', entityIri: c.iri, statement: `"${c.label}" is the entity <${c.iri}>.` }];
    this.translate();
  }

  pickEncoding(o: EncodingOption): void {
    this.clarifications = [...this.clarifications, { kind: 'ENCODING', optionId: o.id, statement: o.statement }];
    this.translate();
  }

  rephrase(): void {
    this.answer = null;
    this.clarifications = [];
    setTimeout(() => this.questionBox?.nativeElement.focus());
  }

  useExample(example: string): void {
    this.question = example;
    this.answer = null;
    this.clarifications = [];
    this.showExamples = false;
  }

  switchTo(providerId: string): void {
    this.onProviderChange(providerId);
  }

  // =========================
  //  Running
  // =========================

  private nlContext(): NlHistoryContext {
    const a = this.answer!;
    return {
      question: this.question.trim(),
      clarifications: this.clarifications,
      generatedSparql: a.sparql ?? '',
      targetEntityIri: a.targetEntityIri,
      createdEntityIris: a.createdEntityIris ?? [],
      provider: a.provider,
      model: a.model
    };
  }

  run(): void {
    const a = this.answer;
    if (!a?.sparql) return;
    if (a.queryType === 'SELECT') {
      this.nlQuery.pendingSparql$.next({ query: a.sparql, type: 'SELECT', autoRun: true, nl: this.nlContext() });
      this.dialogRef.close();
      this.router.navigate(['/sparql']);
      return;
    }
    this.running = true;
    this.error = null;
    this.gestionService.runUpdateQuery(a.sparql, this.nlContext()).subscribe({
      next: () => {
        this.running = false;
        this.gestionService.entitiesChanged$.next();
        const target = a.targetEntityIri;
        if (!target) {
          this.done('Update applied');
          return;
        }
        // Open the entity created or edited, if it still exists (a delete makes it disappear).
        this.gestionService.getEntityDetails(target).subscribe({
          next: () => {
            this.dialogRef.close();
            this.entityDialog.openLoadingTypes(target).subscribe();
          },
          error: () => this.done('Update applied')
        });
      },
      error: err => {
        this.running = false;
        this.error = this.errorText(err);
        this.cdr.detectChanges();
      }
    });
  }

  openInEditor(): void {
    const a = this.answer;
    if (!a?.sparql) return;
    this.nlQuery.pendingSparql$.next({
      query: a.sparql, type: a.queryType === 'UPDATE' ? 'UPDATE' : 'SELECT', autoRun: false, nl: this.nlContext()
    });
    this.dialogRef.close();
    this.router.navigate(['/sparql']);
  }

  copy(): void {
    if (this.answer?.sparql && typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(this.answer.sparql).then(() => this.snackBar.open('Query copied', undefined, { duration: 1500 }));
    }
  }

  close(): void {
    this.dialogRef.close();
  }

  private done(message: string): void {
    this.snackBar.open(message, undefined, { duration: 2500, panelClass: ['snackbar-success'] });
    this.dialogRef.close();
  }

  // =========================
  //  History
  // =========================

  toggleHistory(): void {
    this.showHistory = !this.showHistory;
    if (this.showHistory) this.loadHistory();
  }

  private loadHistory(): void {
    this.nlQuery.history().subscribe({
      next: h => { this.history = h.slice(0, 30); this.cdr.detectChanges(); },
      error: () => { this.history = []; }
    });
  }

  reuse(entry: NlHistoryEntry): void {
    this.question = entry.question;
    this.answer = null;
    this.clarifications = [];
    setTimeout(() => this.questionBox?.nativeElement.focus());
  }

  deleteEntry(entry: NlHistoryEntry): void {
    if (this.confirmDeleteId !== entry.id) {
      this.confirmDeleteId = entry.id;
      return;
    }
    this.confirmDeleteId = null;
    this.nlQuery.deleteHistoryEntry(entry.id).subscribe({ next: () => this.loadHistory() });
  }

  isCorrected(entry: NlHistoryEntry): boolean {
    return entry.edited || (entry.clarifications ?? []).some(c => c.kind === 'ENCODING');
  }

  agentLabel(id: string | null | undefined): string {
    return this.providers.find(p => p.id === id)?.label ?? id ?? '';
  }

  // =========================
  //  Helpers
  // =========================

  get isKeyError(): boolean {
    return !!this.error && /refused|quota|rate limit|credit|unavailable|cannot reach/i.test(this.error);
  }

  private startTimer(): void {
    this.elapsed = 0;
    this.stopTimer();
    this.timer = setInterval(() => { this.elapsed++; this.cdr.detectChanges(); }, 1000);
  }

  private stopTimer(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  private newRequestId(): string {
    try {
      return crypto.randomUUID();
    } catch {
      return Math.random().toString(36).slice(2) + Date.now().toString(36);
    }
  }

  private errorText(err: any): string {
    return err?.error?.error ?? err?.error?.message ?? err?.message ?? 'Something went wrong.';
  }

  shortIri(iri: string): string {
    const i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
    return i >= 0 ? iri.substring(i + 1) : iri;
  }
}
