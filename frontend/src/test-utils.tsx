import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { vi } from 'vitest';
import { createQueryClient } from './api/queryClient';
import type { components } from './api/schema';
import { routes } from './router';

type Schemas = components['schemas'];

export const me: Schemas['MeResponse'] = {
  id: '6f9619ff-8b86-d011-b42d-00c04fc964ff',
  username: 'con_goer',
  globalName: 'Con Goer',
  avatarUrl: 'https://cdn.discordapp.com/embed/avatars/0.png',
  mfaEnabled: false,
  pushEnabled: false,
  roles: ['USER'],
  timeZone: 'America/Chicago',
};

export const guilds: Schemas['GuildResponse'][] = [
  { id: '81384788765712384', name: 'Example Con Community', iconUrl: null, discoverable: false },
  { id: '81384788765712385', name: 'Retro Gamers', iconUrl: null, discoverable: false },
  { id: '81384788765712386', name: 'Cosplay Corner', iconUrl: null, discoverable: true },
];

/** The D-010 error example from the contract. */
export const errorEnvelope: Schemas['ResponseRecordErrorResponseBodyRecord'] = {
  responseType: 'ERROR',
  body: {
    errorType: 'NOT_FOUND',
    errorMessage: 'Not Found',
    correlationId: '3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60',
  },
};

export function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A success response in the D-010 envelope. */
export function envelope(responseType: string, body: unknown) {
  return jsonResponse({ responseType, body });
}

export interface RecordedCall {
  method: string;
  path: string;
  body: unknown;
}

/** `handler` answers each call; an unhandled call fails the test loudly. Returns the recorded calls. */
export function mockApi(handler: (call: RecordedCall) => Response | undefined) {
  const calls: RecordedCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call: RecordedCall = {
        method: init?.method ?? 'GET',
        path: String(input),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      };
      calls.push(call);
      const response = handler(call);
      if (!response) throw new Error(`Unmocked request: ${call.method} ${call.path}`);
      return response;
    }),
  );
  return calls;
}

export function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { retry: false }, mutations: { retry: false } });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}
