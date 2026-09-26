import { inject } from '@angular/core';
import type { ActivatedRouteSnapshot, CanActivateFn } from '@angular/router';
import { Router, Routes } from '@angular/router';

import type { BreadcrumbItem } from './breadcrumb';

/** The first 8 characters of a session id, for the breadcrumb and the title (issue #115). */
function shortSessionId(sessionId: string): string {
  return sessionId.slice(0, 8);
}

/** Builds the breadcrumb and title label of the current user for the Elements view. */
function elementsUserLabel(snapshot: ActivatedRouteSnapshot): string {
  const sessionId = snapshot.queryParamMap.get('sessionId');
  if (sessionId) {
    return `Session ${shortSessionId(sessionId)}`;
  }
  if (snapshot.queryParamMap.get('anonymous') === 'true') {
    return 'Anonymous';
  }
  const userId = snapshot.queryParamMap.get('userId');
  return userId ? `User ${userId}` : 'Elements';
}

/**
 * Sends a request with no `userId`, no `anonymous=true`, and no
 * `sessionId` to the user list. D28 states that the Elements route
 * always carries exactly one of the three (issue #115, step 5).
 */
const requireElementsFilter: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const hasFilter =
    route.queryParamMap.has('userId') ||
    route.queryParamMap.get('anonymous') === 'true' ||
    route.queryParamMap.has('sessionId');
  if (hasFilter) {
    return true;
  }
  const router = inject(Router);
  return router.createUrlTree(['/apps', route.paramMap.get('appId'), 'users']);
};

/**
 * Sends a request with no `anonymous=true` to the user list. The API
 * of issue #113 gives 400 for the Sessions route without that value
 * (`SessionsRoute.kt`, issue #115 correction of decision 1).
 */
const requireSessionsFilter: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  if (route.queryParamMap.get('anonymous') === 'true') {
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
    path: 'apps/:appId/first-pages',
    title: (route: ActivatedRouteSnapshot) =>
      `First pages of App ${route.paramMap.get('appId')} - Octometer`,
    loadComponent: () => import('./first-pages/first-pages').then((m) => m.FirstPages),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => [
        { label: 'Apps', path: '/apps' },
        { label: `App ${snapshot.paramMap.get('appId')}`, path: null },
        { label: 'First pages', path: null },
      ],
    },
  },
  {
    path: 'apps/:appId/sessions',
    // The guard reads anonymous. This route re-runs it on a
    // query-parameter change, not on a path change alone (issue #115).
    runGuardsAndResolvers: 'paramsOrQueryParamsChange',
    canActivate: [requireSessionsFilter],
    title: (route: ActivatedRouteSnapshot) =>
      `Anonymous sessions of App ${route.paramMap.get('appId')} - Octometer`,
    loadComponent: () => import('./sessions/sessions').then((m) => m.Sessions),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => [
        { label: 'Apps', path: '/apps' },
        { label: `App ${snapshot.paramMap.get('appId')}`, path: null },
        { label: 'Sessions', path: null },
      ],
    },
  },
  {
    path: 'apps/:appId/elements',
    // The title and the guard read userId, anonymous, and sessionId.
    // This route re-runs both on a query-parameter change, not on a
    // path change alone.
    runGuardsAndResolvers: 'paramsOrQueryParamsChange',
    canActivate: [requireElementsFilter],
    title: (route: ActivatedRouteSnapshot) =>
      `${elementsUserLabel(route)} of App ${route.paramMap.get('appId')} - Octometer`,
    loadComponent: () => import('./elements/elements').then((m) => m.Elements),
    data: {
      breadcrumb: (snapshot: ActivatedRouteSnapshot): BreadcrumbItem[] => {
        const appId = snapshot.paramMap.get('appId');
        // A session comes from the Sessions view (issue #115), not the
        // Users view. The Sessions entry carries no link: BreadcrumbItem
        // holds a path only, and the Sessions route needs the query
        // parameter anonymous=true, which no plain path can carry.
        const middleEntry = snapshot.queryParamMap.has('sessionId')
          ? { label: 'Sessions', path: null }
          : { label: 'Users', path: `/apps/${appId}/users` };
        return [
          { label: 'Apps', path: '/apps' },
          { label: `App ${appId}`, path: null },
          middleEntry,
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
