import { inject } from '@angular/core';
import type { ActivatedRouteSnapshot, CanActivateFn } from '@angular/router';
import { Router, Routes } from '@angular/router';

import type { BreadcrumbItem } from './breadcrumb';

/** Builds the breadcrumb and title label of the current user for the Elements view. */
function elementsUserLabel(snapshot: ActivatedRouteSnapshot): string {
  if (snapshot.queryParamMap.get('anonymous') === 'true') {
    return 'Anonymous';
  }
  const userId = snapshot.queryParamMap.get('userId');
  return userId ? `User ${userId}` : 'Elements';
}

/**
 * Sends a request with no `userId` and no `anonymous=true` to the user list.
 * D28 states that the Elements route always carries one of the two.
 */
const requireElementsFilter: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const hasFilter =
    route.queryParamMap.has('userId') || route.queryParamMap.get('anonymous') === 'true';
  if (hasFilter) {
    return true;
  }
  const router = inject(Router);
  return router.createUrlTree(['/apps', route.paramMap.get('appId'), 'users']);
};

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'apps' },
  {
    path: 'manage',
    title: 'Manage - Octometer',
    loadComponent: () => import('./manage/manage').then((m) => m.Manage),
    data: {
      breadcrumb: (): BreadcrumbItem[] => [{ label: 'Manage', path: null }],
    },
  },
  {
    path: 'apps',
    title: 'Apps - Octometer',
    loadComponent: () => import('./apps/apps').then((m) => m.Apps),
    data: {
      breadcrumb: (): BreadcrumbItem[] => [{ label: 'Apps', path: null }],
    },
  },
  {
    path: 'apps/:appId/users',
    title: (route: ActivatedRouteSnapshot) =>
      `Users of App ${route.paramMap.get('appId')} - Octometer`,
    loadComponent: () => import('./users/users').then((m) => m.Users),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => [
        { label: 'Apps', path: '/apps' },
        { label: `App ${snapshot.paramMap.get('appId')}`, path: null },
        { label: 'Users', path: null },
      ],
    },
  },
  {
    path: 'apps/:appId/elements',
    // The title and the guard read userId and anonymous. This route re-runs
    // both on a query-parameter change, not on a path change alone.
    runGuardsAndResolvers: 'paramsOrQueryParamsChange',
    canActivate: [requireElementsFilter],
    title: (route: ActivatedRouteSnapshot) =>
      `${elementsUserLabel(route)} of App ${route.paramMap.get('appId')} - Octometer`,
    loadComponent: () => import('./elements/elements').then((m) => m.Elements),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => {
        const appId = snapshot.paramMap.get('appId');
        return [
          { label: 'Apps', path: '/apps' },
          { label: `App ${appId}`, path: null },
          { label: 'Users', path: `/apps/${appId}/users` },
          { label: elementsUserLabel(snapshot), path: null },
        ];
      },
    },
  },
  {
    path: '**',
    title: 'Page not found - Octometer',
    loadComponent: () => import('./not-found/not-found').then((m) => m.NotFound),
    data: {
      breadcrumb: (): BreadcrumbItem[] => [{ label: 'Page not found', path: null }],
    },
  },
];
