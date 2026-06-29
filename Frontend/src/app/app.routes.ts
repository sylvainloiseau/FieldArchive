import { Route } from '@angular/router';
import { GestionProjetsComponent } from './components/gestion-projets/gestion-projets.component';
import { GestionSourcesComponent } from './components/gestion-sources/gestion-sources.component';
import { GestionRessourcesComponent } from './components/gestion-ressources/gestion-ressources.component';
import {FileViewerComponent} from './components/file-viewer/file-viewer.component';

export const routes: Route[] = [
  {
    path: '',
    redirectTo: '/gestion-projets',
    pathMatch: 'full'
  },
  {
    path: 'gestion-projets',
    component: GestionProjetsComponent,
  },
  {
    path: 'gestion-sources',
    component: GestionSourcesComponent,
  },
  {
    path: 'gestion-ressources',
    component: GestionRessourcesComponent,
  },
  {
    path: 'files',
    component: FileViewerComponent,
  },
];