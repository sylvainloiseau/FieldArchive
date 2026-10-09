import { Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject, of, switchMap, catchError, map, EMPTY } from 'rxjs';
import { LinkAction, LinkChangeResult, MatchLink } from '../models/matching.model';
import { MatchingHttpService } from './matching-http.service';
import { LinkChangeConfirmDialogComponent } from '../components/data-matching/link-change-confirm-dialog/link-change-confirm-dialog.component';

/**
 * Applies link changes the same way everywhere: a dry run first; when it has side effects
 * (inferred links added or removed, warnings) the user confirms; then the change; then a
 * snackbar, with "Undo" when the change can be undone.
 */
@Injectable({
  providedIn: 'root'
})
export class MatchingActionsService {

  /** Emits after every applied change (including an undo from the snackbar), so lists can reload. */
  readonly changed$ = new Subject<void>();

  constructor(private http: MatchingHttpService, private dialog: MatDialog, private snackBar: MatSnackBar) {}

  /** Emits the applied result, or completes without emitting when cancelled or failed. */
  applyAction(link: MatchLink, action: LinkAction, note?: string | null): Observable<LinkChangeResult> {
    const verb = action === 'ACCEPT' ? 'Accept' : action === 'REJECT' ? 'Reject' : 'Undo';
    return this.withConfirmation(
      () => this.http.action(link.id, action, true, note),
      () => this.http.action(link.id, action, false, note),
      `${verb} this link?`, verb,
      action === 'UNDO' ? null : () => this.http.action(link.id, 'UNDO', false));
  }

  applyBulk(ids: string[], action: LinkAction): Observable<LinkChangeResult> {
    const verb = action === 'ACCEPT' ? 'Accept' : action === 'REJECT' ? 'Reject' : 'Undo';
    return this.withConfirmation(
      () => this.http.bulkAction(ids, action, true),
      () => this.http.bulkAction(ids, action, false),
      `${verb} ${ids.length} link(s)?`, verb, null, true);
  }

  createManualLink(a: { dataSource: string; iri: string }, b: { dataSource: string; iri: string },
                   note?: string | null): Observable<LinkChangeResult> {
    return this.withConfirmation(
      () => this.http.createManualLink(a, b, true, note),
      () => this.http.createManualLink(a, b, false, note),
      'Link these two entities?', 'Link', null);
  }

  removeOrphaned(pairKey: string | null, dataSource: string | null): Observable<LinkChangeResult> {
    return this.withConfirmation(
      () => this.http.removeOrphaned(pairKey, dataSource, true),
      () => this.http.removeOrphaned(pairKey, dataSource, false),
      'Remove the links of entities that no longer exist?', 'Remove', null, true);
  }

  private withConfirmation(dryRun: () => Observable<LinkChangeResult>, apply: () => Observable<LinkChangeResult>,
                           title: string, confirmLabel: string,
                           undo: (() => Observable<LinkChangeResult>) | null,
                           alwaysConfirm = false): Observable<LinkChangeResult> {
    return dryRun().pipe(
      switchMap(dry => {
        const sideEffects = dry.inferredAdded.length || dry.inferredRemoved.length || dry.warnings.length;
        if (!sideEffects && !alwaysConfirm) return of(true);
        if (alwaysConfirm && !sideEffects && dry.links.length === 0) {
          this.snackBar.open('Nothing to change.', 'Close', { duration: 3000 });
          return of(false);
        }
        return this.dialog.open(LinkChangeConfirmDialogComponent, {
          width: '560px',
          data: {
            title: alwaysConfirm && dry.links.length ? `${title} (${dry.links.length})` : title,
            confirmLabel, result: dry
          }
        }).afterClosed().pipe(map(ok => !!ok));
      }),
      switchMap(ok => ok ? apply() : EMPTY),
      map(result => {
        this.report(result, undo);
        this.changed$.next();
        return result;
      }),
      catchError(err => {
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
        return EMPTY;
      })
    );
  }

  private report(result: LinkChangeResult, undo: (() => Observable<LinkChangeResult>) | null): void {
    const parts: string[] = [];
    if (result.links.length) parts.push(`${result.links.length} link(s) updated`);
    if (result.inferredAdded.length) parts.push(`${result.inferredAdded.length} inferred`);
    if (result.inferredRemoved.length) parts.push(`${result.inferredRemoved.length} inferred link(s) removed`);
    const message = parts.join(' · ') || 'Done';
    const ref = this.snackBar.open(message, undo ? 'Undo' : 'Close', { duration: 6000 });
    if (undo) {
      ref.onAction().pipe(switchMap(() => undo()), catchError(err => {
        this.snackBar.open(MatchingHttpService.errorMessage(err), 'Close', { duration: 8000 });
        return EMPTY;
      })).subscribe(() => {
        this.changed$.next();
        this.snackBar.open('Undone.', 'Close', { duration: 3000 });
      });
    }
  }
}
