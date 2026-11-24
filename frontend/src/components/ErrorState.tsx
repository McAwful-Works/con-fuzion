import { ApiError } from '../api/client';

/** Text is chosen from the status, never from server text. */
function describe(error: unknown): string {
  // A cap, not a rate limit (D-011): the status is the same 429, the errorType tells them apart.
  if (error instanceof ApiError && error.code === 'INVITE_LIMIT') {
    return 'You have a lot of invite links that nobody has used yet. They expire after 24 hours, so try again later.';
  }
  if (error instanceof ApiError && error.code === 'EVENT_LIMIT') {
    return 'You’ve created 20 events in the last 24 hours, which is the most we allow. Try again later.';
  }
  if (error instanceof ApiError && error.status === 429) {
    return 'Too many requests. Wait a moment and try again.';
  }
  return 'Something went wrong. Try again.';
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div role="alert" className="border-line bg-surface-raised rounded-card border p-4">
      <p>{describe(error)}</p>
      {onRetry && (
        <button type="button" onClick={onRetry} className="btn btn-secondary mt-3">
          Try again
        </button>
      )}
    </div>
  );
}
