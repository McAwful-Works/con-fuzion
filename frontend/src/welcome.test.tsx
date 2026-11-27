import { act, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearUnavailable } from './api/maintenance';
import { errorEnvelope, jsonResponse, mockApi, renderApp } from './test-utils';

afterEach(() => {
  vi.unstubAllGlobals();
  clearUnavailable();
});

const signedOut = () =>
  mockApi(({ path }) => (path === '/api/me' ? jsonResponse(errorEnvelope, 401) : undefined));

const signInLinks = () => screen.queryAllByRole('link', { name: 'Sign in with Discord' });

describe('the welcome page, signed out', () => {
  it('says what the app is, and offers sign-in in the top bar and in the page', async () => {
    signedOut();

    renderApp('/welcome');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Meetups for your con crew.' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/choose exactly who can see it/)).toBeInTheDocument();
    expect(signInLinks().map((link) => link.getAttribute('href'))).toEqual([
      '/api/oauth2/authorization/discord',
      '/api/oauth2/authorization/discord',
    ]);
    expect(
      within(screen.getByRole('banner')).getByRole('link', { name: 'Sign in with Discord' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/never get your email, messages or friends list/i)).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Free meetups');
  });

  it('has three “how it works” steps and the privacy promises', async () => {
    signedOut();

    renderApp('/welcome');

    const how = await screen.findByRole('region', { name: 'How it works' });
    expect(
      within(how)
        .getAllByRole('heading', { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(['Sign in, pick your servers', 'Host and choose who sees it', 'Spots fill fairly']);
    const privacy = screen.getByRole('region', { name: 'Your privacy' });
    expect(within(privacy).getAllByRole('listitem')).toHaveLength(3);
  });

  it('shows two made-up meetups and asks the API only who is signed in', async () => {
    const calls = signedOut();

    renderApp('/welcome');

    const examples = await screen.findByRole('list', { name: 'Example meetups' });
    expect(within(examples).getAllByRole('listitem')).toHaveLength(2);
    expect(examples).toHaveTextContent('Board game meetup');
    expect(examples).toHaveTextContent('Cosplay photo walk');
    // Nothing to open: they aren't real events.
    expect(within(examples).queryByRole('link')).toBeNull();

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(calls.map((c) => c.path)).toEqual(['/api/me']);
  });

  it('offers no sign-in while the server is down for maintenance', async () => {
    mockApi(({ path }) =>
      path === '/api/me'
        ? jsonResponse(
            {
              responseType: 'ERROR',
              body: { ...errorEnvelope.body, errorType: 'SERVICE_UNAVAILABLE' },
            },
            503,
          )
        : undefined,
    );

    renderApp('/welcome');

    expect(await screen.findByRole('heading', { name: 'Back soon' })).toBeInTheDocument();
    expect(signInLinks()).toEqual([]);
  });
});
