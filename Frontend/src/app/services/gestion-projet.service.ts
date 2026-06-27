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

  // ── State global : projet actif ──────────────────────────────────────────
  private _activeProject$ = new BehaviorSubject<ProjectDto | null>(null);
  readonly activeProject$ = this._activeProject$.asObservable();

  constructor(private http: HttpClient, private snackBar: MatSnackBar) {}

  // ── GET /api/project/all ─────────────────────────────────────────────────

  /**
   * Retourne la liste de tous les projets connus du backend.
   */
  getAllProjects(): Observable<ProjectDto[]> {
    return this.http.get<ProjectDto[]>(`${this.API_BASE}/list/details`).pipe(
      catchError(err => this.handleError(err, 'Erreur lors du chargement des projets'))
    );
  }

  // ── GET /api/project ─────────────────────────────────────────────────────

  /**
   * Retourne le projet actuellement ouvert (open=false si aucun).
   */
  getActiveProject(): Observable<ProjectDto> {
    return this.http.get<ProjectDto>(`${this.API_BASE}/current`).pipe(
      tap(project => {
        this._activeProject$.next(project.name ? project : null);
        // console.log(this._activeProject$.next(project.name ? project : null))
      }),
      catchError(err => this.handleError(err, 'Erreur lors de la récupération du projet'))
    );
  }

  // ── POST /api/project/open ───────────────────────────────────────────────

  /**
   * Ouvre ou crée un projet RDF.
   */
  openProject(request: OpenProjectRequest): Observable<ProjectDto> {
    return this.http.post<ProjectDto>(`${this.API_BASE}/open`, request).pipe(
      tap(project => {
        this._activeProject$.next(project);
        this.snackBar.open(`Projet  ouvert`, 'Fermer', {
          duration: 3000, panelClass: ['snackbar-success']
        });
      }),
      catchError(err => this.handleError(err, 'Erreur lors de l\'ouverture du projet'))
    );
  }

  deleteProject(projectName: string): Observable<string> {
    return this.http.delete(`${this.API_BASE}/${projectName}`, {
      responseType: 'text'
    } as const);
  }

  // ── DELETE /api/project/close ────────────────────────────────────────────

  /**
   * Ferme le projet actif. Les données restent sur disque.
   */

    closeProject(): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/close`, {}).pipe(
      tap(() => {
        this._activeProject$.next(null);
        this.snackBar.open('Projet fermé', 'Fermer', {
          duration: 3000, panelClass: ['snackbar-info']
        });
      }),
      catchError(err => this.handleError(err, 'Erreur lors de la fermeture du projet'))
    );
  }

  setActiveProject(project: ProjectDto | null): void {
    this._activeProject$.next(project);
  }


  // ── Helpers ───────────────────────────────────────────────────────────────

  get currentActiveProject(): ProjectDto | null {
    return this._activeProject$.value;
  }

  // ── Gestion des erreurs ───────────────────────────────────────────────────

  private handleError(error: HttpErrorResponse, message: string): Observable<never> {
    let errorMessage = message;
    if (error.status === 400) errorMessage = 'Nom de projet manquant ou invalide';
    else if (error.status === 0) errorMessage = 'Impossible de contacter le serveur';
    else if (error.error?.message) errorMessage = error.error.message;

    this.snackBar.open(errorMessage, 'Fermer', {
      duration: 5000, panelClass: ['snackbar-error']
    });
    return throwError(() => new Error(errorMessage));
  }
}