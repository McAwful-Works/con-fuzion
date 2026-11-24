import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  envelope,
  errorEnvelope,
  guilds,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';

afterEach(() => vi.unstubAllGlobals());

describe('auth state', () => {
  it('sends a 401 from /api/me to the landing page', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    const router = renderApp('/settings');

    await screen.findByRole('link', { name: 'Sign in with Discord' });
    expect(router.state.location.pathname).toBe('/welcome');
  });

  it('links the sign-in button to the Discord authorization endpoint', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    renderApp('/welcome');

    const link = await screen.findByRole('link', { name: 'Sign in with Discord' });
    expect(link).toHaveAttribute('href', '/api/oauth2/authorization/discord');
  });

  it('shows a plain message after a failed sign-in and removes the parameter', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    const router = renderApp('/welcome?login=failed');

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in didn’t complete');
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('shows no sign-in message on a normal visit', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    renderApp('/welcome');

    await screen.findByRole('link', { name: 'Sign in with Discord' });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the app shell with the avatar and name when signed in', async () => {
    mockApi(({ path }) => (path === '/api/me' ? envelope('ME', me) : undefined));

    renderApp('/');

    expect(await screen.findByText('Con Goer')).toBeInTheDocument();
    expect(document.querySelector(`img[src="${me.avatarUrl}"]`)).not.toBeNull();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('shows an error state with retry for a non-401 failure, never server text', async () => {
    mockApi(({ path }) =>
      path === '/api/me'
        ? jsonResponse(
            { ...errorEnvelope, body: { ...errorEnvelope.body, errorMessage: 'secret' } },
            500,
          )
        : undefined,
    );

    renderApp('/');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong');
    expect(alert).not.toHaveTextContent('secret');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('sets the time zone from Intl when the account has none', async () => {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const calls = mockApi(({ method, path }) =>
      path === '/api/me'
        ? envelope('ME', method === 'PATCH' ? { ...me, timeZone: zone } : { ...me, timeZone: null })
        : undefined,
    );

    renderApp('/');

    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'PATCH')).toEqual([
        { method: 'PATCH', path: '/api/me', body: { timeZone: zone } },
      ]),
    );
  });

  it('leaves a time zone that is already set alone', async () => {
    const calls = mockApi(({ path }) => (path === '/api/me' ? envelope('ME', me) : undefined));

    renderApp('/');
    await screen.findByText('Con Goer');

    expect(calls.some((c) => c.method === 'PATCH')).toBe(false);
  });

  it('logs out everywhere with a single POST and returns to the landing page', async () => {
    const calls = mockApi(({ method, path }) => {
      if (path === '/api/me') return envelope('ME', me);
      if (path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
      if (method === 'POST' && path === '/api/auth/logout-everywhere') {
        return new Response(null, { status: 204 });
      }
      return undefined;
    });

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Log out everywhere' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    expect(calls.filter((c) => c.path === '/api/auth/logout-everywhere')).toHaveLength(1);
  });
});

describe('servers', () => {
  function serverApi(overrides: (call: RecordedCall) => Response | undefined = () => undefined) {
    return mockApi((call) => {
      const custom = overrides(call);
      if (custom) return custom;
      if (call.path === '/api/me') return envelope('ME', me);
      if (call.method === 'GET' && call.path === '/api/me/guilds') {
        return envelope('GUILD_LIST', { guilds });
      }
      return undefined;
    });
  }

  it('lists servers with discoverable off by default and the privacy copy', async () => {
    serverApi();

    renderApp('/onboarding');

    const first = await screen.findByRole('switch', { name: /Example Con Community/ });
    expect(first).not.toBeChecked();
    expect(screen.getByRole('switch', { name: /Cosplay Corner/ })).toBeChecked();
    expect(screen.getByText(/We never see your messages or other members/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/');
  });

  it('saves a single toggle with PUT /api/me/guilds/{id}', async () => {
    const target = guilds[0]!;
    const calls = serverApi(({ method, path }) =>
      method === 'PUT' && path === `/api/me/guilds/${target.id}`
        ? envelope('GUILD', { ...target, discoverable: true })
        : undefined,
    );

    renderApp('/onboarding');
    fireEvent.click(await screen.findByRole('switch', { name: /Example Con Community/ }));

    await waitFor(() =>
      expect(screen.getByRole('switch', { name: /Example Con Community/ })).toBeChecked(),
    );
    expect(calls.filter((c) => c.method === 'PUT')).toEqual([
      { method: 'PUT', path: `/api/me/guilds/${target.id}`, body: { discoverable: true } },
    ]);
  });

  it('select all sends exactly one bulk PUT, not one per server', async () => {
    const calls = serverApi(({ method, path }) =>
      method === 'PUT' && path === '/api/me/guilds'
        ? envelope('GUILD_LIST', { guilds: guilds.map((g) => ({ ...g, discoverable: true })) })
        : undefined,
    );

    renderApp('/onboarding');
    fireEvent.click(await screen.findByRole('button', { name: 'Select all servers' }));

    await screen.findByRole('button', { name: 'Turn off for all servers' });
    expect(calls.filter((c) => c.method === 'PUT')).toEqual([
      {
        method: 'PUT',
        path: '/api/me/guilds',
        body: { guilds: guilds.map((g) => ({ guildId: g.id, discoverable: true })) },
      },
    ]);
    for (const guild of guilds) {
      expect(screen.getByRole('switch', { name: new RegExp(guild.name) })).toBeChecked();
    }
  });

  it('shows an error state, without server text, when the list fails to load', async () => {
    serverApi(({ method, path }) =>
      method === 'GET' && path === '/api/me/guilds' ? jsonResponse(errorEnvelope, 500) : undefined,
    );

    renderApp('/onboarding');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong');
    expect(alert).not.toHaveTextContent('Not Found');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('shows an inline error and reverts when a toggle fails to save', async () => {
    const target = guilds[0]!;
    serverApi(({ method, path }) =>
      method === 'PUT' && path === `/api/me/guilds/${target.id}`
        ? jsonResponse(errorEnvelope, 500)
        : undefined,
    );

    renderApp('/onboarding');
    fireEvent.click(await screen.findByRole('switch', { name: /Example Con Community/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t save');
    expect(screen.getByRole('switch', { name: /Example Con Community/ })).not.toBeChecked();
  });

  it('sends a 401 from a toggle save to the landing page', async () => {
    const target = guilds[0]!;
    let expired = false;
    mockApi(({ method, path }) => {
      if (path === '/api/me') {
        return expired ? jsonResponse(errorEnvelope, 401) : envelope('ME', me);
      }
      if (method === 'GET' && path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
      if (method === 'PUT' && path === `/api/me/guilds/${target.id}`) {
        expired = true;
        return jsonResponse(errorEnvelope, 401);
      }
      return undefined;
    });

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('switch', { name: /Example Con Community/ }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
  });

  it('reuses the same list under Settings → Servers', async () => {
    serverApi();

    renderApp('/settings');

    expect(await screen.findByRole('switch', { name: /Retro Gamers/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Servers' })).toBeInTheDocument();
  });

  it('explains an empty list', async () => {
    mockApi(({ method, path }) => {
      if (path === '/api/me') return envelope('ME', me);
      if (method === 'GET' && path === '/api/me/guilds') {
        return envelope('GUILD_LIST', { guilds: [] });
      }
      return undefined;
    });

    renderApp('/onboarding');

    expect(await screen.findByText(/didn’t find any Discord servers/)).toBeInTheDocument();
  });
});
