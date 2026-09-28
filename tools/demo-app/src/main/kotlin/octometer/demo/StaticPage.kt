package octometer.demo

/**
 * The static page of the demo app (issue #14, step 4). It loads the
 * built tracker, and it holds three `data-octo` elements and a select
 * box for three demo user ids. The select box sets the cookie
 * `demo_user`. The route `demoUserId` reads that cookie (design decision
 * D19). The tracker option `flushIntervalMs` is 1000. The tracker sends
 * the raw path of the page in each click and in the session start; it
 * takes no route list of its own (design decision D49). The kit derives
 * its own route pattern list from the routes of this app (design
 * decision D46, issue #275); it types no list of its own.
 *
 * <p>The link to `/items/42` shows the derivation: [DemoItemPage] is a
 * parametrized route, so a session start on it stores `/items/:id`, not
 * the raw path (`e2e/tests/octometer.spec.ts`).
 */
object DemoIndexPage {

    val html: String = """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Octometer demo</title>
        </head>
        <body>
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

          const tracker = createTracker({
            endpoint: '/api/octometer/v1/clicks',
            flushIntervalMs: 1000,
          });
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
     * the ingest route).
     */
    fun html(itemId: String): String {
        val escapedItemId = escapeHtmlText(itemId)
        return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Octometer demo item</title>
        </head>
        <body>
        <h1>Item $escapedItemId</h1>
        <p><a href="/">Back to the start page</a></p>
        <p><button type="button" data-octo="demo.item-view">View item</button></p>
        <script type="module">
          import { createTracker } from '/tracker/index.js';

          const tracker = createTracker({
            endpoint: '/api/octometer/v1/clicks',
            flushIntervalMs: 1000,
          });
          tracker.start();
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    /** Escapes [text] for an HTML text position (the five ASCII characters that HTML reserves). */
    private fun escapeHtmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
