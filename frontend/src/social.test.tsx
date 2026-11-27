import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
import {
  envelope,
  errorEnvelope,
  findSignIn,
  jsonResponse,
  me,
  mockApi,
  renderApp,
  type RecordedCall,
} from './test-utils';

type Schemas = components['schemas'];

const ada: Schemas['PersonResponse'] = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Ada',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/1.png',
};
const bo: Schemas['PersonResponse'] = {
  id: '22222222-2222-4222-8222-222222222222',
  displayName: 'Bo',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/2.png',
};
const cy: Schemas['PersonResponse'] = {
  id: '33333333-3333-4333-8333-333333333333',
  displayName: 'Cy',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/3.png',
};
const crew: Schemas['CircleResponse'] = {
  id: '44444444-4444-4444-8444-444444444444',
  name: 'Cosplay crew',
  members: [ada],
};

const invite: Schemas['InviteResponse'] = {
  url: 'https://confuzion.example/invite/AbCdEfGhIjKlMnOpQrStUv',
  expiresAt: '2030-01-02T03:04:05Z',
};
const TOKEN = 'AbCdEfGhIjKlMnOpQrStUv';
const preview: Schemas['InvitePreviewResponse'] = {
  expiresAt: invite.expiresAt,
  inviter: { displayName: 'Ada', avatarUrl: ada.avatarUrl },
};

const noContent = () => new Response(null, { status: 204 });

/** Picks `action` from a person's "…" menu. */
async function choose(person: string, action: string) {
  fireEvent.click(await screen.findByRole('button', { name: `More actions for ${person}` }));
  fireEvent.click(screen.getByRole('button', { name: action }));
}

/** The phone layout's Add button, then the panel's own button. */
async function makeLink() {
  fireEvent.click(await screen.findByRole('button', { name: 'Add a friend' }));
  fireEvent.click(screen.getByRole('button', { name: 'Make an invite link' }));
}

/** From 1024px up. jsdom has no `matchMedia`, so without this the window is a phone's. */
function onDesktop() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query === '(min-width: 64rem)',
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

/** Answers the three People lists: two friends, one circle and one blocked person. */
const peopleLists = ({ method, path }: RecordedCall) => {
  if (method !== 'GET') return undefined;
  if (path === '/api/friends') return envelope('PERSON_LIST', { people: [ada, bo] });
  if (path === '/api/circles') return envelope('CIRCLE_LIST', { circles: [crew] });
  if (path === '/api/blocks') return envelope('PERSON_LIST', { people: [cy] });
  return undefined;
};

/** Signed in, with `extra` answering the social calls a test cares about. */
function signedIn(extra: (call: RecordedCall) => Response | undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.method === 'GET' && (call.path === '/api/friends' || call.path === '/api/blocks')) {
      return envelope('PERSON_LIST', { people: [] });
    }
    if (call.method === 'GET' && call.path === '/api/circles') {
      return envelope('CIRCLE_LIST', { circles: [] });
    }
    return undefined;
  });
}

beforeEach(() => sessionStorage.clear());
afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe('the People tabs', () => {
  it('counts each list, and switches tab without adding to history', async () => {
    signedIn(peopleLists);
    const router = renderApp('/people');

    const tabs = within(await screen.findByRole('navigation', { name: 'People lists' }));
    const friends = tabs.getByRole('link', { name: /^Friends/ });
    await waitFor(() => expect(friends).toHaveTextContent('Friends 2'));
    expect(tabs.getByRole('link', { name: /^Circles/ })).toHaveTextContent('Circles 1');
    expect(tabs.getByRole('link', { name: /^Blocked/ })).toHaveTextContent('Blocked 1');
    expect(friends).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByText('Bo')).toBeInTheDocument();

    fireEvent.click(tabs.getByRole('link', { name: /^Circles/ }));
    expect(await screen.findByRole('link', { name: /Cosplay crew/ })).toHaveAttribute(
      'href',
      `/circles/${crew.id}`,
    );
    expect(router.state.location.search).toBe('?tab=circles');
    expect(router.state.historyAction).toBe('REPLACE');
    // Adding a friend belongs to the Friends tab.
    expect(screen.queryByRole('button', { name: 'Add a friend' })).toBeNull();

    fireEvent.click(tabs.getByRole('link', { name: /^Blocked/ }));
    expect(await screen.findByRole('button', { name: 'Unblock Cy' })).toBeInTheDocument();
  });

  it('closes a friend’s menu on Escape and on a press elsewhere', async () => {
    signedIn(peopleLists);
    renderApp('/people');

    const more = await screen.findByRole('button', { name: 'More actions for Ada' });
    expect(screen.queryByRole('button', { name: 'Unfriend Ada' })).toBeNull();
    fireEvent.click(more);
    expect(more).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(screen.getByRole('button', { name: 'Unfriend Ada' }), { key: 'Escape' });
    expect(screen.queryByRole('button', { name: 'Unfriend Ada' })).toBeNull();
    expect(more).toHaveFocus();

    fireEvent.click(more);
    fireEvent.pointerDown(document.body);
    expect(more).toHaveAttribute('aria-expanded', 'false');
  });

  it('gives focus back to the "…" button when a confirmation closes', async () => {
    signedIn(peopleLists);
    renderApp('/people');

    await choose('Ada', 'Block Ada');
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }),
    );

    expect(screen.getByRole('button', { name: 'More actions for Ada' })).toHaveFocus();
  });
});

describe('invite a friend', () => {
  it('is behind the Add button on phones, and keeps the link when closed', async () => {
    const calls = signedIn(({ method, path }) =>
      method === 'POST' && path === '/api/friends/invites' ? envelope('INVITE', invite) : undefined,
    );
    renderApp('/people');

    const add = await screen.findByRole('button', { name: 'Add a friend' });
    expect(add).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Make an invite link' })).toBeNull();

    await makeLink();
    await screen.findByRole('img', { name: 'QR code for your invite link' });
    fireEvent.click(add);
    expect(screen.queryByRole('button', { name: 'Make a new link' })).toBeNull();
    fireEvent.click(add);
    expect(screen.getByRole('button', { name: 'Make a new link' })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('sits beside the friends list on desktop, with your circles', async () => {
    onDesktop();
    signedIn(peopleLists);
    renderApp('/people');

    const side = within(
      await screen.findByRole('complementary', { name: 'Add a friend, and circles' }),
    );
    expect(side.getByRole('button', { name: 'Make an invite link' })).toBeInTheDocument();
    expect(await side.findByRole('link', { name: /Cosplay crew/ })).toBeInTheDocument();
    expect(side.getByRole('link', { name: 'New circle' })).toHaveAttribute(
      'href',
      '/people?tab=circles',
    );
    expect(screen.queryByRole('button', { name: 'Add a friend' })).toBeNull();
  });

  it('makes no invite until asked, then shows QR, link, expiry and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends')
        return envelope('PERSON_LIST', { people: [] });
      if (method === 'POST' && path === '/api/friends/invites') return envelope('INVITE', invite);
      return undefined;
    });

    renderApp('/friends');
    await screen.findByText(/No friends yet/);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);

    await makeLink();

    const qr = await screen.findByRole('img', { name: 'QR code for your invite link' });
    expect(qr.querySelector('path')?.getAttribute('d')).toBeTruthy();
    expect(screen.getByText(invite.url)).toBeInTheDocument();
    expect(screen.getByText(/Expires/)).toBeInTheDocument();
    // jsdom has no Web Share API, so only Copy is offered.
    expect(screen.queryByRole('button', { name: /Share/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Copy link/ }));
    await screen.findByText('Link copied.');
    expect(writeText).toHaveBeenCalledWith(invite.url);

    // Only asking again makes another one.
    fireEvent.click(screen.getByRole('button', { name: 'Make a new link' }));
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2));
  });

  it('offers Share when the browser has the Web Share API', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, share });
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends')
        return envelope('PERSON_LIST', { people: [] });
      if (method === 'POST' && path === '/api/friends/invites') return envelope('INVITE', invite);
      return undefined;
    });

    renderApp('/friends');
    await makeLink();
    fireEvent.click(await screen.findByRole('button', { name: /Share/ }));

    await waitFor(() =>
      expect(share).toHaveBeenCalledWith(expect.objectContaining({ url: invite.url })),
    );
  });

  it('explains INVITE_LIMIT in plain words', async () => {
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/friends')
        return envelope('PERSON_LIST', { people: [] });
      if (method === 'POST' && path === '/api/friends/invites') {
        return jsonResponse(
          { ...errorEnvelope, body: { ...errorEnvelope.body, errorType: 'INVITE_LIMIT' } },
          429,
        );
      }
      return undefined;
    });

    renderApp('/friends');
    await makeLink();

    expect(await screen.findByRole('alert')).toHaveTextContent(/invite links that nobody has used/);
  });
});

describe('accepting an invite', () => {
  it('shows the inviter, accepts with one POST and lands on Friends', async () => {
    let used = false;
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/friends/invites/${TOKEN}`) {
        // Once accepted the invite is used up, so a refetch would be a 404.
        return used ? jsonResponse(errorEnvelope, 404) : envelope('INVITE_PREVIEW', preview);
      }
      if (method === 'POST' && path === `/api/friends/invites/${TOKEN}/accept`) {
        used = true;
        return envelope('PERSON', ada);
      }
      if (method === 'GET' && path === '/api/friends')
        return envelope('PERSON_LIST', { people: [ada] });
      return undefined;
    });

    const router = renderApp(`/invite/${TOKEN}`);
    expect(await screen.findByText(/Ada invited you to be friends/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Not now' })).toHaveAttribute('href', '/');

    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/people'));
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
    // The accepted invite is never fetched again, so no "isn't valid" flash on the way out.
    expect(calls.filter((c) => c.path === `/api/friends/invites/${TOKEN}`)).toHaveLength(1);
  });

  it('shows one message for every 404 and keeps the token for no one', async () => {
    sessionStorage.setItem('pendingInviteToken', TOKEN);
    signedIn(({ path }) =>
      path === `/api/friends/invites/${TOKEN}` ? jsonResponse(errorEnvelope, 404) : undefined,
    );

    renderApp(`/invite/${TOKEN}`);

    expect(
      await screen.findByRole('heading', { name: 'This invite link isn’t valid anymore' }),
    ).toBeInTheDocument();
    await waitFor(() => expect(sessionStorage.getItem('pendingInviteToken')).toBeNull());
  });

  it('shows the same message when accepting turns out to be a 404', async () => {
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === `/api/friends/invites/${TOKEN}`) {
        return envelope('INVITE_PREVIEW', preview);
      }
      if (method === 'POST') return jsonResponse(errorEnvelope, 404);
      return undefined;
    });

    renderApp(`/invite/${TOKEN}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));

    expect(await screen.findByText('This invite link isn’t valid anymore')).toBeInTheDocument();
  });

  it('keeps the token across sign-in and resumes on the accept page', async () => {
    // Signed out: the preview and /me are both 401.
    mockApi(({ path }) =>
      path === '/api/me' || path.startsWith('/api/friends/invites/')
        ? jsonResponse(errorEnvelope, 401)
        : undefined,
    );

    const first = renderApp(`/invite/${TOKEN}`);
    await findSignIn();
    expect(first.state.location.pathname).toBe('/welcome');
    expect(sessionStorage.getItem('pendingInviteToken')).toBe(TOKEN);
    expect(screen.getByText('Sign in to answer your friend’s invite.')).toBeInTheDocument();

    // Back from Discord, signed in. Nobody is sent anywhere but the in-app accept page.
    vi.unstubAllGlobals();
    signedIn(({ method, path }) =>
      method === 'GET' && path === `/api/friends/invites/${TOKEN}`
        ? envelope('INVITE_PREVIEW', preview)
        : undefined,
    );
    const second = renderApp('/');

    await screen.findByText(/Ada invited you to be friends/);
    expect(second.state.location.pathname).toBe(`/invite/${TOKEN}`);
    expect(sessionStorage.getItem('pendingInviteToken')).toBeNull();
  });

  it('lets a first-time user finish onboarding before the accept page', async () => {
    sessionStorage.setItem('pendingInviteToken', TOKEN);
    signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/me/guilds')
        return envelope('GUILD_LIST', { guilds: [] });
      if (method === 'GET' && path === `/api/friends/invites/${TOKEN}`) {
        return envelope('INVITE_PREVIEW', preview);
      }
      return undefined;
    });

    const router = renderApp('/onboarding');
    await screen.findByRole('heading', { name: 'Where can people find you?' });
    expect(router.state.location.pathname).toBe('/onboarding');

    fireEvent.click(screen.getByRole('link', { name: 'Continue' }));
    await screen.findByText(/Ada invited you to be friends/);
    expect(router.state.location.pathname).toBe(`/invite/${TOKEN}`);
  });
});

describe('friends, removing and blocking', () => {
  const friendsApi = (extra: (call: RecordedCall) => Response | undefined = () => undefined) =>
    signedIn((call) => {
      const custom = extra(call);
      if (custom) return custom;
      if (call.method === 'GET' && call.path === '/api/friends') {
        return envelope('PERSON_LIST', { people: [ada, bo] });
      }
      return undefined;
    });

  it('confirms before removing a friend, and says circles are affected', async () => {
    const calls = friendsApi(({ method, path }) =>
      method === 'DELETE' && path === `/api/friends/${ada.id}` ? noContent() : undefined,
    );

    renderApp('/friends');
    await choose('Ada', 'Unfriend Ada');

    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toHaveTextContent(/out of the other’s circles/);
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove friend' }));
    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.path)).toEqual([
        `/api/friends/${ada.id}`,
      ]),
    );
  });

  it('explains the full cutoff before blocking, and cancel blocks no one', async () => {
    const calls = friendsApi(({ method, path }) =>
      method === 'PUT' && path === `/api/blocks/${bo.id}` ? noContent() : undefined,
    );

    renderApp('/friends');
    await choose('Bo', 'Block Bo');

    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toHaveTextContent('both ways');
    expect(dialog).toHaveTextContent('You stop being friends');
    expect(dialog).toHaveTextContent('taken out of the other’s circles');
    expect(dialog).toHaveTextContent('Neither of you can see the other’s events');
    expect(dialog).toHaveTextContent('none of this is put back');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(calls.some((c) => c.method === 'PUT')).toBe(false);

    await choose('Bo', 'Block Bo');
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Block' }));
    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'PUT').map((c) => c.path)).toEqual([
        `/api/blocks/${bo.id}`,
      ]),
    );
  });

  it('lists blocks in Settings and unblocks with one DELETE', async () => {
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/me/guilds')
        return envelope('GUILD_LIST', { guilds: [] });
      if (method === 'GET' && path === '/api/blocks')
        return envelope('PERSON_LIST', { people: [cy] });
      if (method === 'DELETE' && path === `/api/blocks/${cy.id}`) return noContent();
      return undefined;
    });

    renderApp('/settings');
    expect(await screen.findByText(/doesn’t bring back a friendship/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Unblock Cy' }));

    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.path)).toEqual([
        `/api/blocks/${cy.id}`,
      ]),
    );
  });
});

describe('circles', () => {
  it('creates a circle with a trimmed name and explains a duplicate', async () => {
    let taken = false;
    const calls = signedIn(({ method, path }) => {
      if (method === 'GET' && path === '/api/circles')
        return envelope('CIRCLE_LIST', { circles: [] });
      if (method === 'POST' && path === '/api/circles') {
        return taken ? jsonResponse(errorEnvelope, 409) : envelope('CIRCLE', crew);
      }
      return undefined;
    });

    renderApp('/circles');
    const field = await screen.findByLabelText('Circle name');
    fireEvent.change(field, { target: { value: '  Cosplay crew ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create circle' }));

    await waitFor(() =>
      expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ name: 'Cosplay crew' }),
    );

    taken = true;
    fireEvent.change(screen.getByLabelText('Circle name'), { target: { value: 'cosplay crew' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create circle' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already have a circle');
    // A failed create keeps what was typed, so it can be fixed.
    expect(screen.getByLabelText('Circle name')).toHaveValue('cosplay crew');
  });

  const circleApi = (extra: (call: RecordedCall) => Response | undefined = () => undefined) =>
    signedIn((call) => {
      const custom = extra(call);
      if (custom) return custom;
      if (call.method === 'GET' && call.path === `/api/circles/${crew.id}`) {
        return envelope('CIRCLE', crew);
      }
      if (call.method === 'GET' && call.path === '/api/circles') {
        return envelope('CIRCLE_LIST', { circles: [crew] });
      }
      return undefined;
    });

  it('adds a member found through people search', async () => {
    const calls = circleApi(({ method, path }) => {
      if (method === 'GET' && path === '/api/people?q=bo') {
        return envelope('PERSON_LIST', { people: [ada, bo] });
      }
      if (method === 'PUT' && path === `/api/circles/${crew.id}/members/${bo.id}`)
        return noContent();
      return undefined;
    });

    renderApp(`/circles/${crew.id}`);
    fireEvent.change(await screen.findByLabelText('Search by name'), { target: { value: 'bo' } });

    // Ada is already in the circle, so only Bo can be added.
    const results = await screen.findByRole('list', { name: 'Search results' });
    expect(within(results).getByText('In this circle')).toBeInTheDocument();
    fireEvent.click(within(results).getByRole('button', { name: 'Add Bo' }));

    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'PUT').map((c) => c.path)).toEqual([
        `/api/circles/${crew.id}/members/${bo.id}`,
      ]),
    );
  });

  it('does not search for fewer than 2 characters', async () => {
    const calls = circleApi();

    renderApp(`/circles/${crew.id}`);
    fireEvent.change(await screen.findByLabelText('Search by name'), { target: { value: 'b' } });
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(calls.some((c) => c.path.startsWith('/api/people'))).toBe(false);
  });

  it('removes a member', async () => {
    const calls = circleApi(({ method, path }) =>
      method === 'DELETE' && path === `/api/circles/${crew.id}/members/${ada.id}`
        ? noContent()
        : undefined,
    );

    renderApp(`/circles/${crew.id}`);
    await choose('Ada', 'Remove Ada from Cosplay crew');

    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.path)).toEqual([
        `/api/circles/${crew.id}/members/${ada.id}`,
      ]),
    );
  });

  it('renames a circle with PATCH', async () => {
    const calls = circleApi(({ method, path }) =>
      method === 'PATCH' && path === `/api/circles/${crew.id}`
        ? envelope('CIRCLE', { ...crew, name: 'Panel buddies' })
        : undefined,
    );

    renderApp(`/circles/${crew.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Rename' }));
    fireEvent.change(screen.getByLabelText('Circle name'), { target: { value: 'Panel buddies' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    await waitFor(() =>
      expect(calls.find((c) => c.method === 'PATCH')?.body).toEqual({ name: 'Panel buddies' }),
    );
  });

  it('confirms before deleting a circle, then returns to the list', async () => {
    const calls = circleApi(({ method, path }) =>
      method === 'DELETE' && path === `/api/circles/${crew.id}` ? noContent() : undefined,
    );

    const router = renderApp(`/circles/${crew.id}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete circle' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete circle' }),
    );

    await waitFor(() => expect(router.state.location.search).toBe('?tab=circles'));
    expect(router.state.location.pathname).toBe('/people');
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('shows "not found" for a circle that is not yours', async () => {
    signedIn(({ path }) =>
      path.startsWith('/api/circles/') ? jsonResponse(errorEnvelope, 404) : undefined,
    );

    renderApp(`/circles/${crew.id}`);

    expect(await screen.findByRole('heading', { name: 'Circle not found' })).toBeInTheDocument();
  });
});
