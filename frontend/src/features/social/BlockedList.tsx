import { useBlocks, useUnblock } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { PersonRow } from './PersonRow';

export function BlockedNote() {
  return (
    <p className="text-ink-muted text-sm">
      Blocking cuts you off from each other both ways. Unblocking lifts the block but doesn’t bring
      back a friendship or a circle membership.
    </p>
  );
}

/** Settings → Blocked. Only the blocks you made are listed; unblocking restores nothing. */
export function BlockedList() {
  const blocks = useBlocks();
  const unblock = useUnblock();

  if (blocks.isPending) {
    return (
      <div role="status" aria-label="Loading blocked people" className="space-y-3">
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }
  if (blocks.isError)
    return <ErrorState error={blocks.error} onRetry={() => void blocks.refetch()} />;
  if (blocks.data.length === 0)
    return <p className="text-ink-muted">You haven’t blocked anyone.</p>;

  return (
    <div className="space-y-3">
      <ul className="space-y-2">
        {blocks.data.map((person) => (
          <PersonRow key={person.id} person={person}>
            <button
              type="button"
              className="btn btn-secondary"
              aria-label={`Unblock ${person.displayName}`}
              disabled={unblock.isPending}
              onClick={() => unblock.mutate(person.id)}
            >
              Unblock
            </button>
          </PersonRow>
        ))}
      </ul>
      {unblock.isError && <ErrorState error={unblock.error} />}
    </div>
  );
}
