/**
 * Thrown for every non-2xx response. `code` is the backend's `body.errorType` from the error envelope
 * when present. The server's `errorMessage` is deliberately not exposed: UI text is chosen by `code`
 * and `status`, never by server text. Revisit when the OpenAPI contract (M1) defines the error shape.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  constructor(status: number, code?: string) {
    super(`Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
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
    try {
      // Backend envelope: { responseType: 'ERROR', body: { errorType, errorMessage } }
      const envelope = (await response.json()) as { body?: { errorType?: unknown } } | null;
      const errorType = envelope?.body?.errorType;
      if (typeof errorType === 'string') code = errorType;
    } catch {
      // Not a JSON error body; the status alone describes it.
    }
    throw new ApiError(response.status, code);
  }

  // Callers receive the backend's response envelope as-is until the OpenAPI types land (M1).
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
