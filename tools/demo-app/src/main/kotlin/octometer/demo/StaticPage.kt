package octometer.demo

/**
 * The static page of the demo app (issue #14, step 4). It loads the
 * built tracker, and it holds three `data-octo` elements and a select
 * box for three demo user ids. The select box sets the cookie
 * `demo_user`. The route `demoUserId` reads that cookie (design decision
 * D19). The tracker option `flushIntervalMs` is 1000, and the option
 * `routes` holds the one path of this page, so each click carries a
 * `path`.
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
        <script type="module">
          import { createTracker } from '/tracker/index.js';

          const tracker = createTracker({
            endpoint: '/api/octometer/v1/clicks',
            flushIntervalMs: 1000,
            routes: ['/'],
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
