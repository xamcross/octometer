import type { ActivatedRouteSnapshot } from '@angular/router';
import { Routes } from '@angular/router';

import type { BreadcrumbItem } from './breadcrumb';

/** Builds the breadcrumb label of the current user for the Elements view. */
function elementsUserLabel(snapshot: ActivatedRouteSnapshot): string {
  if (snapshot.queryParamMap.get('anonymous') === 'true') {
    return 'Anonymous';
  }
  const userId = snapshot.queryParamMap.get('userId');
  return userId ? `User ${userId}` : 'Elements';
}

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'apps' },
  {
    path: 'manage',
    title: 'Manage — Octometer',
    loadComponent: () => import('./manage/manage').then((m) => m.Manage),
    data: {
      breadcrumb: (): BreadcrumbItem[] => [{ label: 'Manage', path: null }],
    },
  },
  {
    path: 'apps',
    title: 'Apps — Octometer',
    loadComponent: () => import('./apps/apps').then((m) => m.Apps),
    data: {
      breadcrumb: (): BreadcrumbItem[] => [{ label: 'Apps', path: null }],
    },
  },
  {
    path: 'apps/:appId/users',
    title: 'Users — Octometer',
    loadComponent: () => import('./users/users').then((m) => m.Users),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => [
        { label: 'Apps', path: '/apps' },
        { label: `App ${snapshot.paramMap.get('appId')}`, path: null },
      ],
    },
  },
  {
    path: 'apps/:appId/elements',
    title: 'Elements — Octometer',
    loadComponent: () => import('./elements/elements').then((m) => m.Elements),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => {
        const appId = snapshot.paramMap.get('appId');
        return [
          { label: 'Apps', path: '/apps' },
          { label: `App ${appId}`, path: `/apps/${appId}/users` },
          { label: elementsUserLabel(snapshot), path: null },
        ];
      },
    },
  },
];
