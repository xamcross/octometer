# Add Octometer to a page

## The route patterns

Octometer groups the paths of your app into pages. Type a route pattern list on
`/manage`, in the edit form, one pattern per line. Example: `/orders/:id`,
`/users/:userId/settings`.

Two rules decide the page of each path:

- A path that matches a typed pattern joins the pattern's own page. `/orders/:id`
  groups `/orders/1` and `/orders/2` into one page.
- A path outside the list gets the default rule. A number, a UUID, or a hex segment
  of 8 characters or more becomes `:id`. Each other path stays as it is.
