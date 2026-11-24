import { ApiError } from '../api/client';

/** Text is chosen from the status, never from server text. */
function describe(error: unknown): string {
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
