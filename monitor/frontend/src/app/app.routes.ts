import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'apps',
    loadComponent: () => import('./apps/apps').then((m) => m.Apps),
  },
];
