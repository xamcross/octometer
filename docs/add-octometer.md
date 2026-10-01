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

The attribute `data-user-id` is optional. Read section 7, "Your users",
for its rule.

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

## 7. Your users

Set the attribute `data-user-id` to a pseudonymous id of your signed-in
user, for example an account id. Never set it to an email address: the
tracker drops a value with an `@` character, with no click stored for
it, so it logs it as anonymous instead.

```html
<script src="https://app.0ct0meter.com/tracker.js?v=<hash>"
  data-app-key="<your app key>" data-user-id="<your pseudonymous id>"></script>
```

The id has 1 to 254 characters. A value with a space, or above 254
characters, is also dropped; such a click shows in the users view as
anonymous, not under that id. A value can hold a `/`, for example
`team/42`.

Erase one user's clicks from your app, and from the users view, with
your app's secret key:

```
curl -X DELETE "https://app.0ct0meter.com/api/apps/<your app id>/events?userId=<the user id>" \
  -H "X-Octometer-Secret: <secret key>"
```

Percent-encode the user id in the query string, for example `team%2F42`
for `team/42`. The call needs no session and no `Origin` header: call it
from your own server, when your app erases that user's account. A wrong
secret key answers `404`.
