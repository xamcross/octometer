// The small HTML pages of "GET /confirm" and "GET /remove" (issue #291,
// the brief review correction M2): one button, no script. The button
// posts the token to the write route, so a mail scanner that opens the
// link changes nothing.

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (ch) =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
      })[ch] as string,
  );
}

function renderPage(title: string, bodyHtml: string): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>${escapeHtml(title)} - Octometer</title>
  </head>
  <body>
${bodyHtml}
  </body>
</html>`;
}

export function renderConfirmForm(token: string): string {
  return renderPage(
    'Confirm your address',
    `    <h1>Confirm your address</h1>
    <form method="post" action="/confirm">
      <input type="hidden" name="token" value="${escapeHtml(token)}" />
      <button type="submit">Confirm my address</button>
    </form>`,
  );
}

export function renderRemoveForm(token: string): string {
  return renderPage(
    'Remove your address',
    `    <h1>Remove your address</h1>
    <form method="post" action="/remove">
      <input type="hidden" name="token" value="${escapeHtml(token)}" />
      <button type="submit">Remove my address</button>
    </form>`,
  );
}

export function renderMessagePage(message: string): string {
  return renderPage(message, `    <h1>${escapeHtml(message)}</h1>`);
}

export function renderInvalidLinkPage(): string {
  return renderMessagePage('The link is not valid.');
}

export const HTML_HEADERS = { 'Content-Type': 'text/html; charset=UTF-8' };
