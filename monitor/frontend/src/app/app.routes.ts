import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'apps' },
  {
    path: 'apps',
    loadComponent: () => import('./apps/apps').then((m) => m.Apps),
  },
];
