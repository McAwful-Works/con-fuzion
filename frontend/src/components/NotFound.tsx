import { Link } from 'react-router';

/** The ordinary "nothing here" page: an unknown address, or one that isn't for this viewer. */
export function NotFound() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-ink-muted">There’s nothing at this address.</p>
      <Link to="/" className="btn btn-secondary">
        Back to events
      </Link>
    </div>
  );
}
