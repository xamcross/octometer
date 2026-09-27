/**
 * The hint texts of the manage forms (issue #245). One file holds each
 * text once, so the add form and the edit form show the same words. A
 * template interpolates each text with `{{ }}`, so the angle brackets of
 * a placeholder need no HTML escape.
 */

/** The hint of "Connection string" on the add form. */
export const CONNECTION_STRING_HINT =
  'The MongoDB URI of the database of the app, with the read-only user of the monitor. ' +
  'Example: mongodb+srv://octometer-reader:<password>@<cluster host>/<database>. ' +
  'A local server without a user: mongodb://127.0.0.1:27017.';

/**
 * The hint of "New connection string" on the edit form. The base text
 * plus one sentence: the edit form keeps the stored string when this
 * field stays empty (`edit-app-form.ts`, the request of `onSubmit`).
 */
export const EDIT_CONNECTION_STRING_HINT =
  CONNECTION_STRING_HINT + ' Leave the field empty to keep the stored string.';

/** The hint of "Database" on the add form. The edit form has no database field. */
export const DATABASE_HINT =
  'The name of the database of the app that holds the collection octometer_events. Example: exampledb.';

/**
 * The hint of "Route patterns" on the add form and the edit form. The
 * template adds the three example lines as `<code>` elements once, after
 * this text.
 */
export const PATH_PATTERNS_HINT =
  'The same list as OCTOMETER_PATH_PATTERNS of the app, one pattern per line, in the same order. ' +
  'Leave the field empty for an app without a list.';
