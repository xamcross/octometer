/**
 * The hint texts of the manage forms (issue #245). One file holds each
 * text once, so the add form and the edit form show the same words. A
 * template interpolates each text with `{{ }}`, so the angle brackets of
 * a placeholder need no HTML escape.
 */

/**
 * The hint of "Connection string" on the add form (issue #272). The
 * path of the URI names the database; the form has no separate
 * database field any more.
 */
export const CONNECTION_STRING_HINT =
  'The MongoDB URI of the database of the app, with the read-only user of the monitor. ' +
  'The path of the URI names the database. ' +
  'Example: mongodb+srv://octometer-reader:<password>@<cluster host>/<database>. ' +
  'A local server without a user: mongodb://127.0.0.1:27017/<database>.';

/**
 * The hint of "New connection string" on the edit form. The base text
 * plus one sentence: the edit form keeps the stored string when this
 * field stays empty (`edit-app-form.ts`, the request of `onSubmit`).
 */
export const EDIT_CONNECTION_STRING_HINT =
  CONNECTION_STRING_HINT + ' Leave the field empty to keep the stored string.';

/** The hint of "Origins (one per line)" on the add form (issue #296, D52, M11). */
export const ORIGINS_HINT = 'The form is https://host, without a path.';

/**
 * The hint of "Route patterns (one per line)" on the edit form (issue
 * #299, D5, D40, the example text of #248). A path outside the list
 * gets the default rule of the collector (D50): a number, a UUID, or a
 * hex segment of 8 characters or more becomes `:id`.
 */
export const ROUTE_PATTERNS_HINT =
  'Example: /orders/:id, /users/:userId/settings. ' +
  'A path outside the list gets the default rule: a number, a UUID, or a hex segment becomes :id.';
