import { Plus } from 'lucide-react';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useBlocks, useCircles, useFriends } from '../api/social';
import { useDesktop } from '../components/shell/useDesktop';
import { BlockedList, BlockedNote } from '../features/social/BlockedList';
import { CircleList } from '../features/social/CircleList';
import { FriendList } from '../features/social/FriendList';
import { InvitePanel } from '../features/social/InvitePanel';
import { Circles } from './Circles';

const TABS = [
  { id: 'friends', label: 'Friends', to: '/people' },
  { id: 'circles', label: 'Circles', to: '/people?tab=circles' },
  { id: 'blocked', label: 'Blocked', to: '/people?tab=blocked' },
] as const;

/**
 * `/people`: your friends, or with `?tab=` your circles or the people you've blocked. Adding a friend
 * is beside the friends list on desktop and behind the Add button on phones.
 */
export function People() {
  const [params] = useSearchParams();
  const asked = params.get('tab');
  const tab = asked === 'circles' || asked === 'blocked' ? asked : 'friends';
  const friendsTab = tab === 'friends';
  const desktop = useDesktop();
  const [adding, setAdding] = useState(false);
  const friends = useFriends();
  const circles = useCircles();
  const blocks = useBlocks();
  const counts = {
    friends: friends.data?.length,
    circles: circles.data?.length,
    blocked: blocks.data?.length,
  };

  return (
    // `data-people-page` makes the shell give this page more room.
    <div
      data-people-page
      className="lg:grid lg:grid-cols-[minmax(0,1fr)_18rem] lg:items-start lg:gap-8 xl:grid-cols-[minmax(0,1fr)_20rem]"
    >
      <div className="min-w-0 space-y-4">
        <div className="flex items-end justify-between gap-3">
          <div>
            <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">Your people</p>
            <h1 className="text-[1.75rem] leading-tight font-extrabold lg:text-[2.125rem]">
              People
            </h1>
          </div>
          {friendsTab && !desktop && (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              aria-label="Add a friend"
              aria-expanded={adding}
              onClick={() => setAdding(!adding)}
            >
              <Plus aria-hidden="true" className="size-4" /> Add
            </button>
          )}
        </div>
        {/* Hidden rather than removed, so that closing it doesn't throw away the link just made. */}
        {!desktop && (
          <div hidden={!friendsTab || !adding}>
            <InvitePanel />
          </div>
        )}
        <nav aria-label="People lists" className="seg">
          {TABS.map(({ id, label, to }) => (
            <Link
              key={id}
              to={to}
              replace
              aria-current={tab === id ? 'page' : undefined}
              className="seg-item"
            >
              {label} {counts[id] !== undefined && <span className="text-xs">{counts[id]}</span>}
            </Link>
          ))}
        </nav>
        {friendsTab && (
          <>
            <FriendList />
            <p className="hint">
              Circles are private lists only you see. Use them to open a meetup to a few people at
              once.
            </p>
          </>
        )}
        {tab === 'circles' && <Circles />}
        {tab === 'blocked' && (
          <>
            <BlockedNote />
            <BlockedList />
          </>
        )}
      </div>
      {desktop && (
        <aside aria-label="Add a friend, and circles" hidden={!friendsTab} className="space-y-4">
          <InvitePanel />
          {friendsTab && (
            <section aria-labelledby="side-circles-heading" className="space-y-3">
              <h2 id="side-circles-heading" className="text-base font-extrabold">
                Circles
              </h2>
              <CircleList />
              <Link to="/people?tab=circles" className="btn btn-secondary btn-sm w-full">
                <Plus aria-hidden="true" className="size-4" /> New circle
              </Link>
            </section>
          )}
        </aside>
      )}
    </div>
  );
}
