import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { CommonModule, DatePipe }        from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router }                        from '@angular/router';
import { Subject }                       from 'rxjs';
import { takeUntil, finalize }           from 'rxjs/operators';

import { GestionProjetService } from '../../services/gestion-projet.service';
import { ProjectDto }     from '../../models/project.model';

import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

@Component({
  selector:    'app-gestion-projets',
  standalone:  true,
  imports: [
    CommonModule,         // @if, @for, DatePipe, AsyncPipe…
    FormsModule,          // [(ngModel)]
    ReactiveFormsModule,  // [formGroup], formControlName
    MatSnackBarModule
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
  originalProject: ProjectDto | null = null;

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
    private router:         Router,
    private snackBar : MatSnackBar
  ) {}

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('zipInput') zipInput!: ElementRef<HTMLInputElement>;

  private pendingImportProject: any | null = null;

  onImportFile(project: any): void {
    if (this.activeProject?.name !== project.name) {
      // Import writes into the currently open repository — enforce that here
      // rather than letting the backend fail with a confusing "no project open" error.
      alert(`Open "${project.name}" first before importing into it.`);
      return;
    }
    this.pendingImportProject = project;
    this.fileInput.nativeElement.value = ''; // reset so re-selecting the same file still fires 'change'
    this.fileInput.nativeElement.click();
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !this.pendingImportProject) return;


    this.isLoading = true;

    this.projectService.importTurtleSource(file).subscribe({
      next: (result) => {
        this.isLoading = false;
        //alert(`Imported ${result.tripleCount} triples into source .`);
        alert(`Imported triples into internal datasource .`);
      },
      error: (err) => {
        this.isLoading = false;
        const detail = err.error?.lineNumber
          ? `Line ${err.error.lineNumber}, column ${err.error.columnNumber}: ${err.error.message}`
          : err.error?.message ?? 'Import failed.';
        alert(detail);
      }
    });
  }

  onBackupSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.isLoading = true;

    this.projectService.importBackUpProject(file).subscribe({
      next: (res) => {
        this.isLoading = false;
        alert(res.message ?? 'Imported backup project.');
        this.loadAll();      
      },
      error: (err) => {
        this.isLoading = false;
        alert("Error: " + (err.error?.message ?? 'Import failed.'));
      }
    });
  }

  onImportBackUpProject(project: any): void {
    this.pendingImportProject = project;
    this.zipInput.nativeElement.value = '';
    this.zipInput.nativeElement.click();
  }

  private async saveViaElectron(blob: Blob, filename: string): Promise<void> {
    const buffer = await blob.arrayBuffer();
    const result = await (window as any).electronAPI.saveFile(new Uint8Array(buffer), filename);

    if (!result.success) {
      if (!result.canceled) {
        // show an error toast/snackbar here
        console.error('Save failed:', result.error);
      }
      return;
    }

    // show a success message with result.filePath
    this.snackBar.open('File saved successfully.', 'Close', { duration: 3000 });
  }

  private saveViaBrowser(blob: Blob, filename: string): void {
    if (!blob || blob.size === 0) {
      console.error('Export failed: empty blob received');
      this.snackBar.open('Export failed: no data received.', 'Close', { duration: 5000 });
      return;
    }

    try {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a); // certains navigateurs (Safari) exigent que l'élément soit dans le DOM
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (e) {
      console.error('Export failed:', e);
      this.snackBar.open('Export failed. See console for details.', 'Close', { duration: 5000 });
    }
  }

  async onExportInternal() {
    try {
      const blob = await this.projectService.exportInternalDataSource();
      const filename = `${this.activeProject?.name}-internal-export.ttl`;

      const isElectron = !!(window as any).electronAPI;
      if (isElectron) {
        await this.saveViaElectron(blob, filename);
      } else {
        this.saveViaBrowser(blob, filename);
      }
    } catch (e) {
      console.error('Export internal failed:', e);
      this.snackBar.open('Export failed. See console for details.', 'Close', { duration: 5000 });
    }
  }

  async onExportBackup() {
    try {
      const blob = await this.projectService.exportBackup();
      const filename = `${this.activeProject?.name}-backup.zip`;

      const isElectron = !!(window as any).electronAPI;
      if (isElectron) {
        await this.saveViaElectron(blob, filename);
      } else {
        this.saveViaBrowser(blob, filename);
      }
    } catch (e) {
      console.error('Export backup failed:', e);
      this.snackBar.open('Export failed. See console for details.', 'Close', { duration: 5000 });
    }
  }


  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.buildForm();
    this.loadAll();

    this.projectService.activeProject$
      .pipe(takeUntil(this.destroy$))
      .subscribe(p => (this.activeProject = p));

    this.projectForm.get('name')?.valueChanges.subscribe(value => {
      if (this.projectForm.get('prefix')?.value.startsWith('http://fr.cnrs.lacito.FieldArchive/')) {
        this.projectForm.patchValue({
          prefix: 'http://fr.cnrs.lacito.FieldArchive/' + value 
        });
      }
    });
    this.projectForm.get('prefix')?.valueChanges.subscribe(value => {
      if (value === '') {
        this.projectForm.patchValue({
          prefix: 'http://fr.cnrs.lacito.FieldArchive/'
        });
      }
    });
  }

  goToFiles() {
    this.router.navigate(['/files']);
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
      persistent:  [true],
      prefix:      ['http://fr.cnrs.lacito.FieldArchive/', [Validators.required, Validators.pattern(/^(https?:\/\/)?([\da-z\.-]+)\.([a-z]{2,6})([\/\w \.-]*)*\/?$/)]]
    });
  }

  loadAll(): void {
    this.isLoading = true;

    this.projectService.getAllProjects()
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  (list) => { this.projects = list; console.log("PROJECTS : ", list); this.applyFilters(); },
        error: () => {}
      });

    this.projectService.getActiveProject()
      .pipe(takeUntil(this.destroy$))
      .subscribe({ error: () => {} });
  }

  // ── Actions projet ─────────────────────────────────────────────────────────

  onProjectChange(project: ProjectDto): void {
    console.log("CHANGED PROJECT : ", project);
    this.projectService.setActiveProject(project);
    this.selectedProject = structuredClone(project);
  }

  updateProject(): void {
    if (!this.selectedProject || !this.originalProject || this.isLoading) return;
    this.projectService.updateProject(this.originalProject.name!, { name: this.selectedProject.name, description: this.selectedProject.description }).subscribe({
      next: (success_message) => {
        this.snackBar.open(
          "✅ Project was successfully updated.",
          'Close',
          {
            duration: 5000,
            horizontalPosition: 'center',
            verticalPosition: 'top',
            panelClass: ['snackbar-success']
          }
        );
      },
      error: (error : any) => {
        console.log("Error :", error);
        this.snackBar.open(
          `Failed to update project : "${this.selectedProject?.name}".`,
          'Close',
          {
            duration: 5000,
            horizontalPosition: 'center',
            verticalPosition: 'top',
            panelClass: ['snackbar-error']
          }
        );
      } 
    }); 
  }

  onDeleteProject(projectName: string | null): void {
    if (!projectName || this.isLoading) return;

    const confirmed = confirm(`Are you sure you want to delete "${projectName}"?`);

    if (!confirmed) return;

    this.projectService.deleteProject(projectName).subscribe({
      next: (response) => {
        console.log('Success:', response);
            this.snackBar.open(
            "✅ Project was successfully deleted.",
            'Close',
            {
              duration: 5000,
              horizontalPosition: 'center',
              verticalPosition: 'top',
              panelClass: ['snackbar-success']
            }
          );
        this.closeDetail();
        this.loadAll(); // Refresh the list of projects after deletion
        // refresh list 
      },
      error: (error) => {
        console.error('Error:', error);
        this.snackBar.open(
        `Failed to delete project : "${this.selectedProject?.name}".`,
        'Close',
        {
          duration: 5000,
          horizontalPosition: 'right',
          verticalPosition: 'bottom',
          panelClass: ['snackbar-error']
        }
        );
      }
    });
  }

  onOpenProject(project: ProjectDto): void {
    if (!project.name || this.isLoading) return;
    this.isLoading = true;

    this.projectService.openProject({ name: project.name, persistent: true, prefix: project.prefix })
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
    this.projectForm.reset(
      { persistent: true, name: '', description: '', prefix: 'http://fr.cnrs.lacito.FieldArchive/' }
    );
    this.showCreateModal = true;
  }

  closeCreateModal(): void {
    this.showCreateModal = false;
  }

  onSubmitProject(): void {
    if (!this.projectForm.valid || this.isLoading) return;
    const { name, description, persistent, prefix } = this.projectForm.value;
    this.isLoading = true;

    this.projectService.createProject({ name, description, persistent, prefix })
      .pipe(takeUntil(this.destroy$), finalize(() => (this.isLoading = false)))
      .subscribe({
        next:  () => { this.closeCreateModal(); this.loadAll() },
        error: () => {}
      });
  }
  
  goToSources(): void {
    this.router.navigate(['/gestion-sources']);
  }

  // ── Panneau détail ─────────────────────────────────────────────────────────

  selectProject(project: ProjectDto): void {
    const isSame = this.selectedProject?.name === project.name;

    if (isSame) {
      this.selectedProject = null;
      this.originalProject = null;
      return;
    }

    this.selectedProject = structuredClone(project); // editable copy
    this.originalProject = structuredClone(project);  // original reference
  }

  closeDetail(): void {
    this.selectedProject = null;
    this.originalProject = null;
  }

  isProjectDirty(): boolean {
    if (!this.selectedProject || !this.originalProject) return false;

    return (
      this.selectedProject.name !== this.originalProject.name ||
      this.selectedProject.description !== this.originalProject.description
    );
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