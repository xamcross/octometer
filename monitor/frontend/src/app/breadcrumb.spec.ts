import type { BreadcrumbItem, BreadcrumbNode } from './breadcrumb';
import { buildBreadcrumb } from './breadcrumb';

describe('buildBreadcrumb', () => {
  it('returns an empty list when no route data holds a breadcrumb function', () => {
    const root: BreadcrumbNode = { firstChild: null, data: {} };
    expect(buildBreadcrumb(root)).toEqual([]);
  });

  it('reads the breadcrumb function of the deepest active route', () => {
    const leafItems: BreadcrumbItem[] = [{ label: 'Apps', path: null }];
    const leaf: BreadcrumbNode = { firstChild: null, data: { breadcrumb: () => leafItems } };
    const root: BreadcrumbNode = { firstChild: leaf, data: {} };

    expect(buildBreadcrumb(root)).toEqual(leafItems);
  });

  it('walks past a route level with no breadcrumb function of its own', () => {
    const leafItems: BreadcrumbItem[] = [
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ];
    const leaf: BreadcrumbNode = { firstChild: null, data: { breadcrumb: () => leafItems } };
    const middle: BreadcrumbNode = { firstChild: leaf, data: {} };
    const root: BreadcrumbNode = { firstChild: middle, data: {} };

    expect(buildBreadcrumb(root)).toEqual(leafItems);
  });
});
