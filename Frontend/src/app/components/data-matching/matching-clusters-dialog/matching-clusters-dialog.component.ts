import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatchingHttpService } from '../../../services/matching-http.service';
import { MatchCluster, nodeName } from '../../../models/matching.model';

/** The entities linked across three or more data sources, conflicts first. */
@Component({
  selector: 'app-matching-clusters-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Entities linked across several sources</h2>
    <mat-dialog-content class="text-sm">
      <p *ngIf="error" class="text-red-700">{{ error }}</p>
      <p *ngIf="clusters && !clusters.length" class="text-gray-500 italic">No entity is linked across three sources or more.</p>
      <div *ngFor="let c of clusters" class="mb-3 p-3 border rounded-lg" [ngClass]="c.conflict ? 'border-red-300 bg-red-50' : 'border-gray-200'">
        <div *ngIf="c.conflict" class="text-xs text-red-700 font-medium mb-1">⚠ Conflict in these links</div>
        <div class="flex flex-wrap gap-2 mb-2">
          <span *ngFor="let n of c.nodes" class="px-2 py-1 rounded bg-gray-100">
            {{ name(n) }} <span class="text-xs text-gray-500">({{ n.dataSource }})</span>
          </span>
        </div>
        <div class="text-xs text-gray-600">
          <div *ngFor="let l of c.links">
            {{ name(l.a) }} ≡ {{ name(l.b) }}
            <span class="ml-1 px-1 rounded" [ngClass]="statusClass(l.status)">{{ l.status.toLowerCase() }}</span>
          </div>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button (click)="ref.close()">Close</button></mat-dialog-actions>
  `
})
export class MatchingClustersDialogComponent implements OnInit {
  clusters: MatchCluster[] | null = null;
  error: string | null = null;
  name = nodeName;

  constructor(public ref: MatDialogRef<MatchingClustersDialogComponent>, private http: MatchingHttpService) {}

  ngOnInit(): void {
    this.http.clusters(3).subscribe({
      next: c => this.clusters = c,
      error: err => this.error = MatchingHttpService.errorMessage(err)
    });
  }

  statusClass(status: string): string {
    return status === 'REJECTED' ? 'bg-red-100 text-red-700'
      : status === 'INFERRED' ? 'bg-sky-100 text-sky-700' : 'bg-emerald-100 text-emerald-700';
  }
}
