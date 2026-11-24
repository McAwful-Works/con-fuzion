import { useState } from 'react';
import { useFriends, type Person } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { BlockConfirm, RemoveFriendConfirm } from './PersonActions';
import { PersonRow } from './PersonRow';

function FriendRow({ person }: { person: Person }) {
  const [asking, setAsking] = useState<'remove' | 'block' | null>(null);
  const done = () => setAsking(null);
  return (
    <PersonRow
      person={person}
      below={
        <>
          {asking === 'remove' && <RemoveFriendConfirm person={person} onDone={done} />}
          {asking === 'block' && <BlockConfirm person={person} onDone={done} />}
        </>
      }
    >
      <button
        type="button"
        className="btn btn-secondary"
        aria-label={`Remove ${person.displayName}`}
        onClick={() => setAsking('remove')}
      >
        Remove
      </button>
      <button
        type="button"
        className="btn btn-secondary"
        aria-label={`Block ${person.displayName}`}
        onClick={() => setAsking('block')}
      >
        Block
      </button>
    </PersonRow>
  );
}

export function FriendList() {
  const friends = useFriends();

  if (friends.isPending) {
    return (
      <div role="status" aria-label="Loading your friends" className="space-y-3">
        {[0, 1].map((i) => (
          <Skeleton key={i} className="h-16 w-full" />
        ))}
      </div>
    );
  }
  if (friends.isError)
    return <ErrorState error={friends.error} onRetry={() => void friends.refetch()} />;
  if (friends.data.length === 0) {
    return <p className="text-ink-muted">No friends yet. Invite someone with a link or QR code.</p>;
  }
  return (
    <ul className="space-y-2">
      {friends.data.map((person) => (
        <FriendRow key={person.id} person={person} />
      ))}
    </ul>
  );
}
