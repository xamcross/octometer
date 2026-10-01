# The script tag guide

This guide adds Octometer to a page with one script tag. You install no npm
package. The guide names no real app and no real organisation. Replace each
value in angle brackets with your own value.

## 1. Add the script tag

Register your app in the manage view first. The manage view then shows your
app key and the script tag of this step. Put the tag before the closing
`</body>` tag of each page you track.

```html
<script src="https://app.0ct0meter.com/tracker.js" data-app-key="<your app key>"></script>
```

Replace an older tag that holds `?v=<hash>` with the bare tag of this step.

The attribute `data-app-key` is required. The script logs one console
warning and starts nothing without it.

The attribute `data-endpoint` is optional. Its default value is the origin
of the script's own `src`, plus `/collect`. Set it only when your app
proxies the collector behind a different host.

A later issue adds a user id attribute to this script tag. Read issue #300
for that rule when it lands.

## 2. The npm option

Octometer also ships an npm package, for an app with its own bundler. Read
`kit/tracker/README.md` for `createTracker` and its options. Use the npm
package or the script tag, never both on one page.

## 3. The origins rule

The page's own origin must sit in your app's origin list. Add each origin
in the manage view, for example `https://your-app.example`. A page with an
origin outside that list sends a click that the collector silently drops.

## 4. The app key and the secret key

The app key is public. The script tag above carries it in plain text, in
each page a visitor opens. Put it in the script tag with no concern.

The secret key is not for the page. The manage view shows it one time, at
the registration. Keep it for a call that the erasure route or a different
server-side task needs. Never put the secret key in HTML, in a script
file, or in a public repository.

## 5. A page with a Content Security Policy

A page with a `Content-Security-Policy` header needs two directives for the
served script tag: `script-src` and `connect-src` each need
`https://app.0ct0meter.com`. `script-src` lets the browser load and run the
script. `connect-src` lets the tracker send a click to the collector.

```
Content-Security-Policy: script-src 'self' https://app.0ct0meter.com; connect-src 'self' https://app.0ct0meter.com;
```

Keep each other directive of your own policy. Add the two values above to
your own `script-src` and `connect-src` lists; do not replace the whole
header.

## 6. The route patterns

Octometer groups the paths of your app into pages. Type a route pattern list on
`/manage`, in the edit form, one pattern per line. Example: `/orders/:id`,
`/users/:userId/settings`.

Two rules decide the page of each path:

- A path that matches a typed pattern joins the pattern's own page. `/orders/:id`
  groups `/orders/1` and `/orders/2` into one page.
- A path outside the list gets the default rule. A number, a UUID, or a hex segment
  of 8 characters or more becomes `:id`. Each other path stays as it is.
