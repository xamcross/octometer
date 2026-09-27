/** One entry of the breadcrumb trail. */
export interface BreadcrumbItem {
  readonly label: string;
  /** The link target. Null when the entry is not a link. */
  readonly path: string | null;
  /**
   * The app id of this entry, when it is the app crumb (issue #270). The
   * shell resolves the label from the app-name store when this field is
   * set, and it keeps the given label as the fallback until the name
   * arrives.
   */
  readonly appId?: number;
  /**
   * The extra query parameters of the link, for example `{ anonymous:
   * 'true' }` for the Sessions crumb (issue #270). Absent when the link
   * needs no query parameter.
   */
  readonly queryParams?: Record<string, string>;
}

/** The route-snapshot shape that buildBreadcrumb needs. */
export interface BreadcrumbNode {
  readonly firstChild: BreadcrumbNode | null;
  readonly data: Record<string, unknown>;
}

/** A route-data function that builds the breadcrumb trail of one view. */
export type BreadcrumbBuilder<T extends BreadcrumbNode = BreadcrumbNode> = (
  node: T,
) => BreadcrumbItem[];

/**
 * Builds the breadcrumb trail of the active route.
 * It reads the `breadcrumb` route-data function of the deepest active route.
 */
export function buildBreadcrumb<T extends BreadcrumbNode>(root: T): BreadcrumbItem[] {
  const leaf = deepestChild(root);
  const builder = leaf.data['breadcrumb'] as BreadcrumbBuilder<T> | undefined;
  return builder ? builder(leaf) : [];
}

function deepestChild<T extends BreadcrumbNode>(node: T): T {
  let current = node;
  while (current.firstChild) {
    current = current.firstChild as T;
  }
  return current;
}
