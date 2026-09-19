import { Component, OnInit } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { GestionProjetService } from '../../services/gestion-projet.service';

interface NavItem {
  label: string;
  path: string;
  needsProject: boolean;
}

@Component({
  selector: 'app-nav-bar',
  imports: [AsyncPipe, RouterLink, RouterLinkActive],
  templateUrl: './nav-bar.component.html',
  styleUrl: './nav-bar.component.scss'
})
export class NavBarComponent implements OnInit {

  readonly navItems: NavItem[] = [
    { label: 'Datasources', path: '/gestion-sources', needsProject: true },
    { label: 'Entities', path: '/gestion-ressources', needsProject: true },
    { label: 'SPARQL', path: '/sparql', needsProject: true },
  ];

  readonly activeProject$;

  constructor(private projectService: GestionProjetService, private router: Router) {
    this.activeProject$ = this.projectService.activeProject$;
  }

  ngOnInit(): void {
    // The backend (ProjectContext) is the source of truth, e.g. after a page reload.
    this.projectService.getActiveProject().subscribe({ error: () => {} });
  }

  onCloseProject(): void {
    this.projectService.closeProject().subscribe({
      next: () => this.router.navigate(['/gestion-projets']),
      error: () => {}
    });
  }
}
