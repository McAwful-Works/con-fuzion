import { markUnavailable } from './maintenance';
import type { components, paths } from './schema';

/** An API path relative to the `/api` root, checked against the generated contract paths. */
export type ContractPath = keyof paths extends infer P
  ? P extends `/api${infer R}`
    ? R
    : never
  : never;

/** Fills a contract path template such as `/friends/{userId}`, URL-encoding each value. */
export function fillPath(template: ContractPath, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => encodeURIComponent(params[key] ?? ''));
}

type ErrorEnvelope = components['schemas']['ResponseRecordErrorResponseBodyRecord'];

/**
 * Thrown for every non-2xx response. `code` is the backend's `body.errorType` from the D-010 error
 * envelope when present. The server's `errorMessage` is deliberately not exposed: UI text is chosen by
 * `code` and `status`, never by server text. `correlationId` is for support and logs; the UI doesn't
 * show it by default.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly correlationId: string | undefined;

  constructor(status: number, code?: string, correlationId?: string) {
    super(`Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

/** A 403 or 404: for this viewer there's nothing there, and the UI says no more than that. */
export function isNotThere(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 403 || error.status === 404);
}

/** Retry server hiccups a couple of times, but never a 4xx (a 401 must reach the sign-in redirect). */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status < 500) return false;
  return failureCount < 2;
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  if (!match?.[1]) return undefined;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

export interface ApiOptions {
  method?: string;
  /** Serialised as JSON. */
  body?: unknown;
  signal?: AbortSignal;
  /** Sent as `If-None-Match`; see `apiConditional`. */
  ifNoneMatch?: string;
}

/** Sends the request and throws an `ApiError` for every non-2xx response, except a 304 when `allow304`. */
async function send(path: string, options: ApiOptions, allow304 = false): Promise<Response> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.ifNoneMatch) headers['If-None-Match'] = options.ifNoneMatch;
  if (!SAFE_METHODS.has(method)) {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  const response = await fetch(`/api${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'same-origin',
    signal: options.signal,
  });

  if (allow304 && response.status === 304) return response;
  if (!response.ok) {
    if (response.status === 503) markUnavailable();
    let code: string | undefined;
    let correlationId: string | undefined;
    try {
      // D-010: { responseType: 'ERROR', body: { errorType, errorMessage, correlationId } }
      const envelope = (await response.json()) as Partial<ErrorEnvelope> | null;
      const { errorType, correlationId: id } = envelope?.body ?? {};
      if (typeof errorType === 'string') code = errorType;
      if (typeof id === 'string') correlationId = id;
    } catch {
      // Not a JSON error body; the status alone describes it.
    }
    throw new ApiError(response.status, code, correlationId);
  }
  return response;
}

async function readBody<T>(response: Response): Promise<T> {
  // A 204 has no body. Otherwise callers receive the D-010 envelope `{ responseType, body }` as-is.
  // A redirect that ends on an HTML page (Spring's default logout) is a success with nothing to read.
  if (!response.headers.get('Content-Type')?.includes('json')) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Same-origin JSON call. `path` is relative to the API root, for example `/me`. */
export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  return readBody<T>(await send(path, options));
}

/** The result of a conditional GET: the body and its ETag, or "nothing changed" (a 304). */
export type Conditional<T> =
  { notModified: true } | { notModified: false; body: T; etag: string | null };

/**
 * A GET that sends `ifNoneMatch` (the last ETag) and treats a bodiless 304 as "nothing changed",
 * so the caller keeps the data it has. Setting the header ourselves makes the browser pass the 304
 * through instead of answering from its own HTTP cache.
 */
export async function apiConditional<T>(
  path: string,
  options: { ifNoneMatch?: string; signal?: AbortSignal } = {},
): Promise<Conditional<T>> {
  const response = await send(path, options, true);
  if (response.status === 304) return { notModified: true };
  return {
    notModified: false,
    body: await readBody<T>(response),
    etag: response.headers.get('ETag'),
  };
}
