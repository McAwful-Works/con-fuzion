import { Ban, Flag, UserMinus } from 'lucide-react';
import { useState } from 'react';
import { useFriends, type Person } from '../../api/social';
import { CardList } from '../../components/CardList';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { ReportSheet } from '../reports/ReportSheet';
import { BlockConfirm, RemoveFriendConfirm } from './PersonActions';
import { PersonMenu } from './PersonMenu';
import { PersonRow } from './PersonRow';

function FriendRow({ person }: { person: Person }) {
  const [asking, setAsking] = useState<'remove' | 'block' | 'report' | null>(null);
  const done = () => setAsking(null);
  const name = person.displayName;
  return (
    <PersonRow
      person={person}
      below={
        <>
          {asking === 'remove' && <RemoveFriendConfirm person={person} onDone={done} />}
          {asking === 'block' && <BlockConfirm person={person} onDone={done} />}
          {asking === 'report' && (
            <ReportSheet
              targetType="USER"
              targetId={person.id}
              label={name}
              blockable={person}
              onDone={done}
            />
          )}
        </>
      }
    >
      <PersonMenu
        name={name}
        actions={[
          {
            text: 'Unfriend',
            label: `Unfriend ${name}`,
            icon: UserMinus,
            onSelect: () => setAsking('remove'),
          },
          { text: 'Block', label: `Block ${name}`, icon: Ban, onSelect: () => setAsking('block') },
          {
            text: 'Report',
            label: `Report ${name}`,
            icon: Flag,
            onSelect: () => setAsking('report'),
          },
        ]}
      />
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
    <CardList aria-label="Your friends">
      {friends.data.map((person) => (
        <FriendRow key={person.id} person={person} />
      ))}
    </CardList>
  );
}
