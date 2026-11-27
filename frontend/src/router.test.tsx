import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  envelope,
  errorEnvelope,
  findSignIn,
  guilds,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';

afterEach(() => vi.unstubAllGlobals());

const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, 100)));

describe('auth state', () => {
  it('sends a 401 from /api/me to the landing page', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    const router = renderApp('/settings');

    await findSignIn();
    expect(router.state.location.pathname).toBe('/welcome');
  });

  it('links the sign-in button to the Discord authorization endpoint', async () => {
    mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

    renderApp('/welcome');

    const link = await findSignIn();
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

    await findSignIn();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the app shell with the avatar and name when signed in', async () => {
    mockApi(({ path }) => (path === '/api/me' ? envelope('ME', me) : undefined));

    renderApp('/');

    expect(await screen.findByText('Con Goer')).toBeInTheDocument();
    expect(document.querySelector(`img[src="${me.avatarUrl}"]`)).not.toBeNull();
    expect(screen.getAllByRole('navigation', { name: 'Main' })).toHaveLength(2);
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
    expect(
      screen.getByText(/never shows us your messages or a server’s member list/),
    ).toBeInTheDocument();
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

  it('Turn on all sends exactly one bulk PUT, not one per server', async () => {
    const calls = serverApi(({ method, path }) =>
      method === 'PUT' && path === '/api/me/guilds'
        ? envelope('GUILD_LIST', { guilds: guilds.map((g) => ({ ...g, discoverable: true })) })
        : undefined,
    );

    renderApp('/onboarding');
    const all = await screen.findByRole('switch', { name: 'Turn on all' });
    expect(all).not.toBeChecked();
    fireEvent.click(all);

    await waitFor(() => expect(all).toBeEnabled());
    expect(calls.filter((c) => c.method === 'PUT')).toEqual([
      {
        method: 'PUT',
        path: '/api/me/guilds',
        body: {
          guilds: [...guilds]
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((g) => ({ guildId: g.id, discoverable: true })),
        },
      },
    ]);
    for (const guild of guilds) {
      expect(screen.getByRole('switch', { name: new RegExp(guild.name) })).toBeChecked();
    }
    expect(all).toBeChecked();
  });

  it('turns every server off again when all are on, with one bulk PUT', async () => {
    const on = guilds.map((g) => ({ ...g, discoverable: true }));
    const calls = serverApi(({ method, path }) => {
      if (path !== '/api/me/guilds') return undefined;
      const list = method === 'PUT' ? guilds.map((g) => ({ ...g, discoverable: false })) : on;
      return envelope('GUILD_LIST', { guilds: list });
    });

    renderApp('/onboarding');
    const all = await screen.findByRole('switch', { name: 'Turn on all' });
    expect(all).toBeChecked();
    fireEvent.click(all);

    await waitFor(() => expect(all).not.toBeChecked());
    const puts = calls.filter((c) => c.method === 'PUT');
    expect(puts).toHaveLength(1);
    const body = puts[0]!.body as { guilds: { discoverable: boolean }[] };
    expect(body.guilds.map((g) => g.discoverable)).toEqual([false, false, false]);
  });

  it('shows the first character of a name with no icon, a whole emoji included', async () => {
    const dice = { ...guilds[0]!, name: '🎲 Dice Night' };
    serverApi(({ method, path }) =>
      method === 'GET' && path === '/api/me/guilds'
        ? envelope('GUILD_LIST', { guilds: [dice] })
        : undefined,
    );

    renderApp('/onboarding');

    const list = await screen.findByRole('list', { name: 'Your servers' });
    expect(within(list).getByText('🎲')).toBeInTheDocument();
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

  it('sends a 401 from a toggle save to the landing page, and stays there', async () => {
    const target = guilds[0]!;
    let expired = false;
    const calls = mockApi(({ method, path }) => {
      if (path === '/api/me') {
        return expired ? jsonResponse(errorEnvelope, 401) : envelope('ME', me);
      }
      if (method === 'GET' && path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
      if (method === 'PUT' && path === `/api/me/guilds/${target.id}`) {
        expired = true;
        return jsonResponse(errorEnvelope, 401);
      }
      if (path.startsWith('/api/notifications')) {
        return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
      }
      return undefined;
    });

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('switch', { name: /Example Con Community/ }));
    await findSignIn();

    // Wait before looking: a redirect loop never settles, even if the landing page shows for a moment.
    await pause();
    const settled = calls.length;
    await pause();

    expect(calls).toHaveLength(settled);
    expect(await findSignIn()).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/welcome');
    // No signed-in navigation is left on screen.
    expect(screen.queryByText('Con Goer')).toBeNull();
    expect(screen.queryByRole('link', { name: /Notifications/ })).toBeNull();
  });

  it('sorts servers case-insensitively', async () => {
    const unsorted = [
      { ...guilds[0]!, id: '81384788765712391', name: 'zebra con' },
      { ...guilds[0]!, id: '81384788765712392', name: 'Apple Con' },
      { ...guilds[0]!, id: '81384788765712393', name: 'banana con' },
    ];
    mockApi(({ method, path }) => {
      if (path === '/api/me') return envelope('ME', me);
      if (method === 'GET' && path === '/api/me/guilds') {
        return envelope('GUILD_LIST', { guilds: unsorted });
      }
      return undefined;
    });

    renderApp('/onboarding');

    const list = await screen.findByRole('list', { name: 'Your servers' });
    const switches = within(list).getAllByRole('switch');
    expect(switches.map((s) => s.closest('li')?.textContent)).toEqual([
      expect.stringContaining('Apple Con'),
      expect.stringContaining('banana con'),
      expect.stringContaining('zebra con'),
    ]);
  });

  it('signs out with POST /api/auth/logout and returns to the landing page', async () => {
    const calls = mockApi(({ method, path }) => {
      if (path === '/api/me') return envelope('ME', me);
      if (method === 'GET' && path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
      if (method === 'POST' && path === '/api/auth/logout')
        return new Response(null, { status: 204 });
      return undefined;
    });

    const router = renderApp('/settings');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/welcome'));
    expect(calls.filter((c) => c.path === '/api/auth/logout')).toHaveLength(1);
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
