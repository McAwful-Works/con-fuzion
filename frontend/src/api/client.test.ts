import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './client';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('api client', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockResolvedValue(json({}));
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=tok%3D123';
  });

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  function sentHeaders() {
    const init = fetchMock.mock.calls[0]?.[1];
    return new Headers(init?.headers);
  }

  it('sends the XSRF cookie as X-XSRF-TOKEN on mutations', async () => {
    await api('/events', { method: 'POST', body: { title: 'x' } });

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/events');
    expect(fetchMock.mock.calls[0]?.[1]?.credentials).toBe('same-origin');
    expect(sentHeaders().get('X-XSRF-TOKEN')).toBe('tok=123');
  });

  it('does not send the token on reads', async () => {
    await api('/events');

    expect(sentHeaders().has('X-XSRF-TOKEN')).toBe(false);
  });

  it('maps the D-010 error envelope to ApiError, keeping the correlation id but not server text', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          responseType: 'ERROR',
          body: {
            errorType: 'NOT_FOUND',
            errorMessage: 'Not Found',
            correlationId: '3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60',
          },
        },
        404,
      ),
    );

    const error = await api('/me/guilds/1', { method: 'PUT' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
      correlationId: '3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60',
    });
    expect((error as ApiError).message).not.toContain('Not Found');
  });

  it('returns undefined for a 204 with no body', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(api('/auth/logout-everywhere', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('treats an empty success body as undefined', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 200 }));

    await expect(api('/events/1/rsvp', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('maps non-JSON error bodies to ApiError with the status only', async () => {
    fetchMock.mockResolvedValue(new Response('<html>boom</html>', { status: 502 }));

    const error = await api('/events').catch((e: unknown) => e);

    expect(error).toMatchObject({ status: 502, code: undefined });
  });
});
