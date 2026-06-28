import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, BehaviorSubject } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { ProjectDto, OpenProjectRequest } from '../models/project.model';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({
  providedIn: 'root'
})
export class GestionProjetService {

  private readonly API_BASE = 'http://localhost:8080/projects';

  // ── Global State ──────────────────────────────────────────
  private _activeProject$ = new BehaviorSubject<ProjectDto | null>(null);
  readonly activeProject$ = this._activeProject$.asObservable();

  constructor(private http: HttpClient, private snackBar: MatSnackBar) {}

  // ── GET /api/project/all ─────────────────────────────────────────────────

  /**
   * Returns a list of all projects with their details.
   */
  getAllProjects(): Observable<ProjectDto[]> {
    return this.http.get<ProjectDto[]>(`${this.API_BASE}/list/details`).pipe(
      catchError(err => this.handleError(err, 'Error while loading the projects'))
    );
  }

  // ── GET /api/project ─────────────────────────────────────────────────────

  /**
   * Returns the currently open project (open=false if none).
   */
  getActiveProject(): Observable<ProjectDto> {
    return this.http.get<ProjectDto>(`${this.API_BASE}/current`).pipe(
      tap(project => {
        this._activeProject$.next(project.name ? project : null);
        // console.log(this._activeProject$.next(project.name ? project : null))
      }),
      catchError(err => this.handleError(err, 'Error while retrieving the project'))
    );
  }

  // ── POST /api/project/open ───────────────────────────────────────────────

  /**
   * Open or create an RDF Project.
   */
  openProject(request: OpenProjectRequest): Observable<ProjectDto> {
    return this.http.post<ProjectDto>(`${this.API_BASE}/open`, request).pipe(
      tap(project => {
        this._activeProject$.next(project);
        this.snackBar.open(`Project opened`, 'Close', {
          duration: 3000, panelClass: ['snackbar-success']
        });
      }),
      catchError(err => this.handleError(err, 'Error while opening the project'))
    );
  }

  deleteProject(projectName: string): Observable<string> {
    return this.http.delete(`${this.API_BASE}/${projectName}`, {
      responseType: 'text'
    } as const);
  }

  updateProject(oldProjectName: string, newProject: any): Observable<any> {
    console.log("OBJECT OF UPDATE : ", newProject);

    return this.http.put<any>(`${this.API_BASE}/${oldProjectName}`, newProject).pipe(
      tap(response => {
        console.log("Project updated:", response?.message ?? response);
      }),
      catchError(err => {
        console.error("Update project failed:", err);
        return throwError(() => err);
      })
    );
  }

  // ── DELETE /api/project/close ────────────────────────────────────────────

  /**
   * Close the active project. The data remains on disk.
   */

    closeProject(): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/close`, {}).pipe(
      tap(() => {
        this._activeProject$.next(null);
        this.snackBar.open('Project closed', 'Close', {
          duration: 3000, panelClass: ['snackbar-info']
        });
      }),
      catchError(err => this.handleError(err, 'Error while closing the project'))
    );
  }

  setActiveProject(project: ProjectDto | null): void {
    this._activeProject$.next(project);
  }


  // ── Helpers ───────────────────────────────────────────────────────────────

  get currentActiveProject(): ProjectDto | null {
    return this._activeProject$.value;
  }

  // ──  Error Handling ───────────────────────────────────────────────────

  private handleError(error: HttpErrorResponse, message: string): Observable<never> {
    let errorMessage = message;
    if (error.status === 400) errorMessage = 'Name of project is invalid or already exists';
    else if (error.status === 0) errorMessage = 'Impossible to reach the server.';
    else if (error.error?.message) errorMessage = error.error.message;

    this.snackBar.open(errorMessage, 'Fermer', {
      duration: 5000, panelClass: ['snackbar-error']
    });
    return throwError(() => new Error(errorMessage));
  }


}