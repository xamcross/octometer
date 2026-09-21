/** One entry of the breadcrumb trail. */
export interface BreadcrumbItem {
  readonly label: string;
  /** The link target. Null when the entry is not a link. */
  readonly path: string | null;
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
