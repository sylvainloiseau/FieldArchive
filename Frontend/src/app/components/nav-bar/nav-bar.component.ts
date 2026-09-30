import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatAutocompleteModule, MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { Subscription } from 'rxjs';
import { GestionProjetService } from '../../services/gestion-projet.service';
import { NlHistoryEntry, NlQueryService } from '../../services/nl-query.service';
import { NlQueryDialogComponent } from '../nl-query-dialog/nl-query-dialog.component';
import { TextNormalize } from './text-normalize';

interface NavItem {
  label: string;
  path: string;
  needsProject: boolean;
}

@Component({
  selector: 'app-nav-bar',
  imports: [AsyncPipe, FormsModule, RouterLink, RouterLinkActive, MatAutocompleteModule],
  templateUrl: './nav-bar.component.html',
  styleUrl: './nav-bar.component.scss'
})
export class NavBarComponent implements OnInit, OnDestroy {

  readonly navItems: NavItem[] = [
    { label: 'Datasources', path: '/gestion-sources', needsProject: true },
    { label: 'Entities', path: '/gestion-ressources', needsProject: true },
    { label: 'SPARQL', path: '/sparql', needsProject: true },
  ];

  readonly activeProject$;

  @ViewChild('askInput') askInput?: ElementRef<HTMLInputElement>;
  @ViewChild(MatAutocompleteTrigger) autocomplete?: MatAutocompleteTrigger;

  /** The question typed in the header field. */
  question = '';
  private recent: NlHistoryEntry[] | null = null;
  private projectSub?: Subscription;
  private hasProject = false;
  readonly isMac = typeof navigator !== 'undefined' && /Mac/i.test(navigator.platform ?? '');

  constructor(private projectService: GestionProjetService, private router: Router,
              private dialog: MatDialog, private nlQuery: NlQueryService) {
    this.activeProject$ = this.projectService.activeProject$;
  }

  ngOnInit(): void {
    // The backend (ProjectContext) is the source of truth, e.g. after a page reload.
    this.projectService.getActiveProject().subscribe({ error: () => {} });
    // Recent questions belong to a project: forget them when it changes.
    this.projectSub = this.activeProject$.subscribe((p: any) => {
      this.hasProject = !!p?.name;
      this.recent = null;
    });
  }

  ngOnDestroy(): void {
    this.projectSub?.unsubscribe();
  }

  onCloseProject(): void {
    this.projectService.closeProject().subscribe({
      next: () => this.router.navigate(['/gestion-projets']),
      error: () => {}
    });
  }

  // =========================
  //  Question field
  // =========================

  /** Ctrl/⌘+K focuses the question field from anywhere in the app. */
  @HostListener('document:keydown', ['$event'])
  onGlobalKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && this.hasProject) {
      event.preventDefault();
      this.askInput?.nativeElement.focus();
    }
  }

  onFocus(): void {
    if (this.recent === null) {
      this.recent = [];
      this.nlQuery.history().subscribe({ next: h => this.recent = h, error: () => {} });
    }
  }

  /** Up to 5 recent questions containing the typed words. */
  get suggestions(): string[] {
    const words = TextNormalize.words(this.question);
    const seen = new Set<string>();
    const out: string[] = [];
    for (const e of this.recent ?? []) {
      const q = TextNormalize.words(e.question).join(' ');
      if (words.length && !words.every(w => q.includes(w))) continue;
      if (seen.has(e.question) || e.question === this.question) continue;
      seen.add(e.question);
      out.push(e.question);
      if (out.length >= 5) break;
    }
    return out;
  }

  onEnter(): void {
    // Enter on a highlighted suggestion only fills the field; the next Enter asks.
    if (this.autocomplete?.panelOpen && this.autocomplete.activeOption) return;
    this.ask();
  }

  onEscape(): void {
    this.autocomplete?.closePanel();
    this.question = '';
    // the autocomplete trigger also handles Escape: clear the element too, not only the model
    if (this.askInput) this.askInput.nativeElement.value = '';
    this.askInput?.nativeElement.blur();
  }

  /** Opens the question dialog; with text, it starts from a first proposal. */
  ask(): void {
    const q = this.question.trim();
    this.autocomplete?.closePanel();
    this.dialog.open(NlQueryDialogComponent, {
      width: '760px',
      maxWidth: '95vw',
      restoreFocus: false, // focusing the field again would reopen its suggestions
      data: { initialQuestion: q || undefined }
    });
    this.question = '';
    this.recent = null; // a new question may be recorded: reload on next focus
    this.askInput?.nativeElement.blur();
  }
}
