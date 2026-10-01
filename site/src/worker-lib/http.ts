// The host check and the origin check of issue #291 (the brief review
// correction M2, M3). The host check keeps a preview with the production
// D1 binding from writing a row; the origin check is the CSRF guard of
// "POST /api/waitlist".

export function isSameHost(url: URL, siteUrl: string): boolean {
  try {
    return url.host === new URL(siteUrl).host;
  } catch {
    return false;
  }
}

export function isSameOrigin(request: Request, siteUrl: string): boolean {
  const origin = request.headers.get('Origin');
  if (!origin) {
    return false;
  }
  try {
    return new URL(origin).origin === new URL(siteUrl).origin;
  } catch {
    return false;
  }
}
