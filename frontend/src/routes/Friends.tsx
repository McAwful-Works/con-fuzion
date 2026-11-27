import { Link } from 'react-router';
import { FriendList } from '../features/social/FriendList';
import { InvitePanel } from '../features/social/InvitePanel';

export function Friends() {
  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Friends</h1>
      <InvitePanel />
      <section aria-labelledby="friends-heading" className="space-y-3">
        <h2 id="friends-heading" className="text-xl font-semibold">
          Your friends
        </h2>
        <FriendList />
      </section>
      <p className="text-ink-muted text-sm">
        Want to group people for your meetups?{' '}
        <Link to="/people?tab=circles" className="underline">
          Make a circle
        </Link>
        .
      </p>
    </div>
  );
}
