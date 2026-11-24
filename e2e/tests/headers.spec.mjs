import { expect, test } from './hooks.mjs';

// The security headers are part of the contract (docs/security.md): on the SPA shell, a deep link and the API.
const paths = [
  ['the SPA shell', '/'],
  ['a deep link', '/onboarding'],
  ['an API success', '/api/actuator/health'],
  ['an API error', '/api/me'],
];

for (const [label, path] of paths) {
  test(`11. security headers on ${label} (${path})`, async ({ request }) => {
    const headers = (await request.get(path)).headers();
    const csp = headers['content-security-policy'] ?? '';
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(headers['strict-transport-security']).toMatch(/max-age=\d+/);
    expect(headers['strict-transport-security']).toContain('includeSubDomains');
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
  });
}
