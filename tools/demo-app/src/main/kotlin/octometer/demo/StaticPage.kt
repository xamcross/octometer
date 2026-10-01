package octometer.demo

/**
 * The static page of the demo app (issue #14, step 4; issue #297). It
 * loads the built tracker, and it holds three `data-octo` elements and
 * a select box for three demo user ids. The select box sets the cookie
 * `demo_user`. The route `demoUserId` reads that cookie (design decision
 * D19). The tracker option `flushIntervalMs` is 1000.
 *
 * Issue #297 (D50): the page reads `collectorUrl` and `appKey` as two
 * `data-*` attributes on `<body>`, so the end-to-end suite's global
 * setup can register the demo app through `POST /api/apps` and pass
 * back the hosted collector's URL and app key (the brief review of
 * 2026-10-01, M6, M7), with no compose-time secret baked into the
 * built page. A blank `collectorUrl` (the standalone run, with no
 * suite) keeps the demo app's own kit ingest route of design decision
 * D46; that route then still derives its own route pattern list from
 * this app's routes. With `collectorUrl` set, the page sends every
 * click to the hosted collector instead, and the demo app's own kit
 * route gets no events (the maintainer's note of 2026-10-01).
 *
 * The tracker sends the raw path of the page in each click and in the
 * session start; it takes no route list of its own (design decision
 * D49).
 *
 * <p>The link to `/items/42` shows the derivation: [DemoItemPage] is a
 * parametrized route, so a session start on it stores `/items/:id`, not
 * the raw path (`e2e/tests/octometer.spec.ts`).
 */
object DemoIndexPage {

    fun html(collectorUrl: String, appKey: String): String {
        val escapedCollectorUrl = escapeHtmlAttribute(collectorUrl)
        val escapedAppKey = escapeHtmlAttribute(appKey)
        return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Octometer demo</title>
        </head>
        <body data-octo-collector-url="$escapedCollectorUrl" data-octo-app-key="$escapedAppKey">
        <h1>Octometer demo</h1>
        <p>Select a demo user. Then click a button. Each button sends one click to the ingest route.</p>
        <p>
          <label for="demo-user">Demo user</label>
          <select id="demo-user">
            <option value="">(no user)</option>
            <option value="amy">amy</option>
            <option value="ben">ben</option>
            <option value="cleo">cleo</option>
          </select>
        </p>
        <p>
          <button type="button" data-octo="demo.button-one">Button one</button>
          <button type="button" data-octo="demo.button-two">Button two</button>
          <button type="button" data-octo="demo.button-three">Button three</button>
        </p>
        <p>
          <a href="/items/42">View item 42</a>
        </p>
        <script type="module">
          import { createTracker } from '/tracker/index.js';

          // Issue #297 (D50): a non-empty collectorUrl sends every
          // click to the hosted collector, with the app key in the
          // header X-Octometer-Key. A blank collectorUrl (the
          // standalone run, with no suite) keeps the demo app's own
          // kit ingest route.
          const collectorUrl = document.body.dataset.octoCollectorUrl;
          const appKey = document.body.dataset.octoAppKey;
          const tracker = createTracker(
            collectorUrl
              ? { endpoint: collectorUrl, appKey: appKey || undefined, flushIntervalMs: 1000 }
              : { endpoint: '/api/octometer/v1/clicks', flushIntervalMs: 1000 },
          );
          tracker.start();

          const select = document.getElementById('demo-user');

          // MINOR 9 of the Ktor review: read the cookie at load, and set
          // the select value. The page then shows the real user, also
          // after a reload.
          const match = document.cookie.match(/(?:^|; )demo_user=([^;]*)/);
          if (match) {
            select.value = decodeURIComponent(match[1]);
          }

          select.addEventListener('change', () => {
            if (select.value === '') {
              document.cookie = 'demo_user=; path=/; SameSite=Lax; max-age=0';
            } else {
              document.cookie = 'demo_user=' + select.value + '; path=/; SameSite=Lax; max-age=86400';
            }
          });
        </script>
        </body>
        </html>
    """.trimIndent()
    }
}

/**
 * The item page of the demo app, at the parametrized route `/items/{id}`
 * (design decision D46, issue #275). It holds one `data-octo` element,
 * so a click on it, after a signed-in session start, proves that the
 * kit stores the derived pattern `/items/:id`, never the raw path.
 */
object DemoItemPage {

    /**
     * Builds the page for [itemId], the raw path parameter of the
     * route. This method escapes it for the HTML text position, because
     * a client chooses the value of a path parameter (contract rule
     * C39 does not apply here; this route is a page of the app, not
     * the ingest route). [collectorUrl] and [appKey] follow the rule of
     * [DemoIndexPage.html] (issue #297).
     */
    fun html(itemId: String, collectorUrl: String, appKey: String): String {
        val escapedItemId = escapeHtmlText(itemId)
        val escapedCollectorUrl = escapeHtmlAttribute(collectorUrl)
        val escapedAppKey = escapeHtmlAttribute(appKey)
        return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Octometer demo item</title>
        </head>
        <body data-octo-collector-url="$escapedCollectorUrl" data-octo-app-key="$escapedAppKey">
        <h1>Item $escapedItemId</h1>
        <p><a href="/">Back to the start page</a></p>
        <p><button type="button" data-octo="demo.item-view">View item</button></p>
        <script type="module">
          import { createTracker } from '/tracker/index.js';

          const collectorUrl = document.body.dataset.octoCollectorUrl;
          const appKey = document.body.dataset.octoAppKey;
          const tracker = createTracker(
            collectorUrl
              ? { endpoint: collectorUrl, appKey: appKey || undefined, flushIntervalMs: 1000 }
              : { endpoint: '/api/octometer/v1/clicks', flushIntervalMs: 1000 },
          );
          tracker.start();
        </script>
        </body>
        </html>
        """.trimIndent()
    }
}

/** Escapes [text] for an HTML text position (the five ASCII characters that HTML reserves). */
private fun escapeHtmlText(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Escapes [text] for an HTML attribute position (issue #297): the same
 * five characters as [escapeHtmlText]. A quote character inside the
 * attribute value, for example a connection detail with an `&`, then
 * stays inside the attribute, with no early close of it.
 */
private fun escapeHtmlAttribute(text: String): String = escapeHtmlText(text)
