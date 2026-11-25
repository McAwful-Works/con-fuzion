import { ApiError } from '../api/client';

const CONFLICTS: Record<string, string> = {
  SIGNUP_NOT_OPEN: 'Signup hasn’t opened yet. Try again when the countdown ends.',
  SIGNUP_CLOSED: 'Signup is closed because the event has started.',
  RSVP_REMOVED: 'You can’t rejoin this event.',
  CAPACITY_BELOW_CONFIRMED:
    'That’s fewer spots than the number of people already confirmed. Choose a higher number, or remove people first.',
  OFFER_EXPIRED:
    'This offer ran out before it was accepted. You can join again, at the back of the line.',
  NO_OFFER: 'There’s no offer to accept any more.',
  HOST_CANNOT_LEAVE: 'You’re the host, so you can’t leave. Cancel the event instead.',
};

/** Text is chosen from the status, never from server text. */
function describe(error: unknown): string {
  // A cap, not a rate limit (D-011): the status is the same 429, the errorType tells them apart.
  if (error instanceof ApiError && error.code === 'INVITE_LIMIT') {
    return 'You have a lot of invite links that nobody has used yet. They expire after 24 hours, so try again later.';
  }
  if (error instanceof ApiError && error.code === 'REPORT_LIMIT') {
    return 'You’ve sent 10 reports in the last 24 hours, which is the most we allow. Try again later.';
  }
  if (error instanceof ApiError && error.code === 'EVENT_LIMIT') {
    return 'You’ve created 20 events in the last 24 hours, which is the most we allow. Try again later.';
  }
  // D-013's domain 409s. The codes are the contract; the wording is ours.
  if (error instanceof ApiError && error.code && Object.hasOwn(CONFLICTS, error.code)) {
    return CONFLICTS[error.code]!;
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
