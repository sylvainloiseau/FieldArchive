import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule, DatePipe }        from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router }                        from '@angular/router';
import { Subject }                       from 'rxjs';
import { takeUntil, finalize }           from 'rxjs/operators';

import { GestionProjetService } from '../../services/gestion-projet.service';
import { ProjectDto }     from '../../models/project.model';

@Component({
  selector:    'app-gestion-projets',
  standalone:  true,
  imports: [
    CommonModule,         // @if, @for, DatePipe, AsyncPipe…
    FormsModule,          // [(ngModel)]
    ReactiveFormsModule,  // [formGroup], formControlName
  ],
  providers:   [DatePipe],
  templateUrl: './gestion-projets.component.html',
  styleUrls:   ['./gestion-projets.component.scss']
})
export class GestionProjetsComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // ── State ──────────────────────────────────────────────────────────────────
  projects:          ProjectDto[] = [];
  filteredProjects:  ProjectDto[] = [];
  paginatedProjects: ProjectDto[] = [];

  activeProject:   ProjectDto | null = null;
  selectedProject: ProjectDto | null = null;

  isLoading = false;

  // ── Modal ──────────────────────────────────────────────────────────────────
  showCreateModal = false;
  projectForm!: FormGroup;

  // ── Recherche / Tri ────────────────────────────────────────────────────────
  searchTerm = '';
  sortField: 'name' | 'created' | 'lastModified' = 'lastModified';
  sortAsc   = false;

  // ── Pagination ─────────────────────────────────────────────────────────────
  currentPage = 0;
  pageSize    = 10;

  constructor(
    private fb:             FormBuilder,
    private projectService: GestionProjetService,
    private router:         Router
  ) {}

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.buildForm();
    this.loadAll();

    this.projectService.activeProject$
      .pipe(takeUntil(this.destroy$))
      .subscribe(p => (this.activeProject = p));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  private buildForm(): void {
    this.projectForm = this.fb.group({
      name:        ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_-]+$/)]],
      description: [''],
      persistent:  [true]
    });
  }

  loadAll(): void {
    this.isLoading = true;

    this.projectService.getAllProjects()
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  (list) => { this.projects = list; this.applyFilters(); },
        error: () => {}
      });

    this.projectService.getActiveProject()
      .pipe(takeUntil(this.destroy$))
      .subscribe({ error: () => {} });
  }

  // ── Actions projet ─────────────────────────────────────────────────────────

  onOpenProject(project: ProjectDto): void {
    if (!project.name || this.isLoading) return;
    this.isLoading = true;

    this.projectService.openProject({ name: project.name, persistent: true })
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  () => this.router.navigate(['/gestion-ressources']),
        error: () => {}
      });
  }

  onCloseProject(): void {
    if (this.isLoading) return;
    this.isLoading = true;

    this.projectService.closeProject()
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  () => { this.selectedProject = null; this.loadAll(); },
        error: () => {}
      });
  }

  refreshAll(): void {
    this.loadAll();
  }

  // ── Modal ──────────────────────────────────────────────────────────────────

  openCreateModal(): void {
    this.projectForm.reset({ persistent: true, name: '', description: '' });
    this.showCreateModal = true;
  }

  closeCreateModal(): void {
    this.showCreateModal = false;
  }

  onSubmitProject(): void {
    if (!this.projectForm.valid || this.isLoading) return;
    const { name, description, persistent } = this.projectForm.value;
    this.isLoading = true;

    this.projectService.openProject({ name, description, persistent })
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  () => { this.closeCreateModal(); this.router.navigate(['/gestion-sources']); },
        error: () => {}
      });
  }
      goToSources(): void {
      this.router.navigate(['/gestion-sources']);
    }

  // ── Panneau détail ─────────────────────────────────────────────────────────

  selectProject(project: ProjectDto): void {
    this.selectedProject = this.selectedProject?.name === project.name ? null : project;
  }

  closeDetail(): void {
    this.selectedProject = null;
  }

  // ── Recherche / Tri ────────────────────────────────────────────────────────

  onSearch():      void { this.currentPage = 0; this.applyFilters(); }
  onSort():        void { this.currentPage = 0; this.applyFilters(); }
  toggleSortOrder(): void { this.sortAsc = !this.sortAsc; this.applyFilters(); }

  private applyFilters(): void {
    let result = [...this.projects];

    if (this.searchTerm.trim()) {
      const term = this.searchTerm.toLowerCase();
      result = result.filter(p =>
        p.name?.toLowerCase().includes(term) ||
        p.description?.toLowerCase().includes(term)
      );
    }

    result.sort((a, b) => {
      const valA = (this.sortField === 'name' ? a.name : this.sortField === 'created' ? a.created : a.lastModified) ?? '';
      const valB = (this.sortField === 'name' ? b.name : this.sortField === 'created' ? b.created : b.lastModified) ?? '';
      return this.sortAsc ? valA.localeCompare(valB) : valB.localeCompare(valA);
    });

    this.filteredProjects = result;
    this.updatePagination();
  }

  // ── Pagination ─────────────────────────────────────────────────────────────

  get totalPages():     number   { return Math.max(1, Math.ceil(this.filteredProjects.length / this.pageSize)); }
  get pageNumbers():    number[] { return Array.from({ length: this.totalPages }, (_, i) => i); }
  get paginationStart():number   { return this.currentPage * this.pageSize; }
  get paginationEnd():  number   { return Math.min(this.paginationStart + this.pageSize, this.filteredProjects.length); }

  private updatePagination(): void {
    this.paginatedProjects = this.filteredProjects.slice(this.paginationStart, this.paginationEnd);
  }

  prevPage():        void { if (this.currentPage > 0) { this.currentPage--; this.updatePagination(); } }
  nextPage():        void { if (this.currentPage < this.totalPages - 1) { this.currentPage++; this.updatePagination(); } }
  goToPage(p: number): void { this.currentPage = p; this.updatePagination(); }
  onPageSizeChange():  void { this.currentPage = 0; this.updatePagination(); }

  // ── Utils ──────────────────────────────────────────────────────────────────

  copyToClipboard(value: string): void {
    navigator.clipboard.writeText(value);
  }

  /** Renvoie la valeur du champ nom pour l'affichage dans le chemin disque */
  get formName(): string {
    return this.projectForm.get('name')?.value || 'nom-projet';
  }
}