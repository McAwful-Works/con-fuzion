/** Thrown for every non-2xx response. `code` and `message` come from the JSON error body when present. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  constructor(status: number, code?: string, message?: string) {
    super(message ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

export interface ApiOptions {
  method?: string;
  /** Serialised as JSON. */
  body?: unknown;
  signal?: AbortSignal;
}

/** Same-origin JSON call. `path` is relative to the API root, for example `/me`. */
export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
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

  if (!response.ok) {
    let code: string | undefined;
    let message: string | undefined;
    try {
      const error: unknown = await response.json();
      if (error && typeof error === 'object') {
        const fields = error as { code?: unknown; message?: unknown };
        if (typeof fields.code === 'string') code = fields.code;
        if (typeof fields.message === 'string') message = fields.message;
      }
    } catch {
      // Not a JSON error body; the status alone describes it.
    }
    throw new ApiError(response.status, code, message);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
