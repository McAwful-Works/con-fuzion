import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from './api/schema';
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

type Schemas = components['schemas'];

const cy: Schemas['PersonResponse'] = {
  id: '33333333-3333-4333-8333-333333333333',
  displayName: 'Cy',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/3.png',
};

function signedIn(extra: (call: RecordedCall) => Response | undefined = () => undefined) {
  return mockApi((call) => {
    const custom = extra(call);
    if (custom) return custom;
    if (call.path === '/api/me') return envelope('ME', me);
    if (call.path === '/api/me/guilds') return envelope('GUILD_LIST', { guilds });
    if (call.path === '/api/blocks') return envelope('PERSON_LIST', { people: [cy] });
    if (call.path.startsWith('/api/notifications')) {
      return envelope('NOTIFICATION_PAGE', { items: [], nextCursor: null, unreadCount: 0 });
    }
    return jsonResponse(errorEnvelope, 404);
  });
}

const group = async (name: string) => within(await screen.findByRole('region', { name }));

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('You', () => {
  it('shows who you are, and rows to your servers and blocked people', async () => {
    signedIn();
    renderApp('/you');

    expect(await screen.findByRole('heading', { level: 1, name: 'Con Goer' })).toBeInTheDocument();
    expect(screen.getByText('@con_goer · from Discord')).toBeInTheDocument();
    const discovery = await group('Discovery');
    const servers = discovery.getByRole('link', { name: /^Servers/ });
    expect(servers).toHaveAttribute('href', '/onboarding');
    await waitFor(() => expect(servers).toHaveTextContent('Discoverable in 1 of 3'));
    const blocked = discovery.getByRole('link', { name: /^Blocked people/ });
    expect(blocked).toHaveAttribute('href', '/people?tab=blocked');
    await waitFor(() => expect(blocked).toHaveTextContent('1 person'));
    // No page or support link exists until M7.
    expect((await group('About')).queryByRole('link')).toBeNull();
  });

  it('keeps the appearance choice on this device and applies it at once', async () => {
    const calls = signedIn();
    const applied = vi.fn();
    window.addEventListener('cf-appearance', applied);
    renderApp('/you');

    const choice = within(await screen.findByRole('radiogroup', { name: 'Appearance' }));
    expect(choice.getByRole('radio', { name: 'Automatic' })).toBeChecked();

    fireEvent.click(choice.getByRole('radio', { name: 'Dark' }));
    expect(choice.getByRole('radio', { name: 'Dark' })).toBeChecked();
    expect(localStorage.getItem('cf-appearance')).toBe('dark');
    expect(applied).toHaveBeenCalledTimes(1);

    fireEvent.click(choice.getByRole('radio', { name: 'Automatic' }));
    expect(localStorage.getItem('cf-appearance')).toBeNull();
    // On the device only: nothing is sent.
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
    window.removeEventListener('cf-appearance', applied);
  });

  it('saves a chosen time zone to the profile', async () => {
    const calls = signedIn(({ method, path }) =>
      method === 'PATCH' && path === '/api/me'
        ? envelope('ME', { ...me, timeZone: 'Europe/Paris' })
        : undefined,
    );
    renderApp('/you');

    const box = await screen.findByRole('combobox', { name: 'Your time zone' });
    expect((box as HTMLInputElement).value).toMatch(/^America\/Chicago/);
    fireEvent.click(box);
    fireEvent.change(box, { target: { value: 'Europe/Paris' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
        { timeZone: 'Europe/Paris' },
      ]),
    );
    expect((box as HTMLInputElement).value).toMatch(/^Europe\/Paris/);
  });

  it('saves quick choices one after the other, ending on the last', async () => {
    const releases: (() => void)[] = [];
    const sent: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== 'PATCH') {
          if (String(input) === '/api/me') return envelope('ME', me);
          return jsonResponse(errorEnvelope, 404);
        }
        const { timeZone } = JSON.parse(String(init.body)) as { timeZone: string };
        sent.push(timeZone);
        await new Promise<void>((resolve) => releases.push(resolve));
        return envelope('ME', { ...me, timeZone });
      }),
    );
    renderApp('/you');
    const box = (await screen.findByRole('combobox', {
      name: 'Your time zone',
    })) as HTMLInputElement;
    const choose = (zone: string) => {
      fireEvent.click(box);
      fireEvent.change(box, { target: { value: zone } });
      fireEvent.keyDown(box, { key: 'Enter' });
    };

    choose('Europe/Paris');
    await waitFor(() => expect(sent).toEqual(['Europe/Paris']));
    choose('America/Chicago');
    // The second waits for the first, and the field shows it meanwhile.
    await waitFor(() => expect(box.value).toMatch(/^America\/Chicago/));
    expect(sent).toEqual(['Europe/Paris']);
    act(() => releases[0]!());
    await waitFor(() => expect(sent).toEqual(['Europe/Paris', 'America/Chicago']));
    act(() => releases[1]!());

    await waitFor(() => expect(box.value).toMatch(/^America\/Chicago/));
  });

  it('says so when the time zone can’t be saved', async () => {
    signedIn(({ method }) => (method === 'PATCH' ? jsonResponse(errorEnvelope, 500) : undefined));
    renderApp('/you');

    const box = await screen.findByRole('combobox', { name: 'Your time zone' });
    fireEvent.click(box);
    fireEvent.change(box, { target: { value: 'Europe/Paris' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t save your time zone');
    expect((box as HTMLInputElement).value).toMatch(/^America\/Chicago/);
  });

  it('offers to install even after the setup’s hint was dismissed', async () => {
    localStorage.setItem('cf-install-hint-dismissed', '1');
    const prompt = vi.fn(async () => undefined);
    signedIn();
    renderApp('/you');
    const notifications = await group('Notifications');
    expect(notifications.queryByRole('button', { name: 'Install' })).toBeNull();

    act(() => {
      window.dispatchEvent(
        Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt }),
      );
    });
    fireEvent.click(notifications.getByRole('button', { name: 'Install' }));

    expect(prompt).toHaveBeenCalled();
    expect(notifications.getByText(/If you installed it/)).toBeInTheDocument();
  });
});
