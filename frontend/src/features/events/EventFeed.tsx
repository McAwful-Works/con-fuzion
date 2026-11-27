import { Plus } from 'lucide-react';
import { Link } from 'react-router';
import { useEventFeed, type EventScope, type FeedFilters } from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { dayParts } from './cardTime';
import { EventCard } from './EventCard';
import { findOverlaps } from './schedule';
import { groupByDay } from './time';

const EMPTY: Record<Exclude<EventScope, 'feed'>, string> = {
  hosting:
    'You’re not hosting anything yet. Events you create show up here, including cancelled ones.',
  attending:
    'Nothing on your schedule yet. Events you join, wait for or host show up here, so you can see them all in one place.',
};

/** Card-shaped blocks while the first page loads. */
function LoadingCards() {
  return (
    <div role="status" aria-label="Loading events" className="space-y-3">
      {[0, 1, 2].map((i) => (
        <div key={i} className="card flex gap-3 p-3.5 lg:gap-4.5 lg:p-4.5">
          <Skeleton className="h-14 w-15 shrink-0 lg:w-19" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-20" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        </div>
      ))}
    </div>
  );
}

/** Discover with nothing in it: host something, or turn on more servers. */
function EmptyDiscover() {
  return (
    <div className="card space-y-3 p-5">
      <h2 className="text-xl font-extrabold">No upcoming meetups yet</h2>
      <p className="text-ink-muted">
        Meetups from your friends, circles and servers show up here. Turn on the servers you’re in
        to see more.
      </p>
      <div className="flex flex-col gap-3 pt-1 sm:flex-row">
        <Link to="/events/new" className="btn btn-primary">
          <Plus aria-hidden="true" className="size-5" />
          Host a meetup
        </Link>
        <Link to="/you" className="btn btn-secondary">
          Turn on servers
        </Link>
      </div>
    </div>
  );
}

/** A search or filter that matches nothing. */
function NoMatches({ onClear }: { onClear: () => void }) {
  return (
    <div className="card space-y-3 p-5">
      <h2 className="text-xl font-extrabold">No meetups match</h2>
      <p className="text-ink-muted">Try a different search, or clear the filters.</p>
      <button type="button" className="btn btn-secondary w-full sm:w-auto" onClick={onClear}>
        Clear filters
      </button>
    </div>
  );
}

/** What a search or filter found, in words. */
function found(count: number, more: boolean): string {
  if (count === 0) return 'No meetups match';
  if (more) return `More than ${count} meetups match`;
  return count === 1 ? '1 meetup matches' : `${count} meetups match`;
}

type Feed = ReturnType<typeof useEventFeed>;

interface Props {
  scope: EventScope;
  /** Discover's search and filters. */
  filters?: FeedFilters;
  /** True while the filters aren't known yet: show the placeholders and ask for nothing. */
  waiting?: boolean;
  /** Given by the list that can be searched and filtered. */
  onClearFilters?: () => void;
}

/** Upcoming events grouped by day in each event's own time zone, loaded a page at a time. */
export function EventFeed({ scope, filters = {}, waiting = false, onClearFilters }: Props) {
  const feed = useEventFeed(scope, filters, !waiting);
  const filtered = Object.keys(filters).length > 0;
  const count = feed.data?.pages.reduce((sum, page) => sum + page.events.length, 0);

  return (
    <>
      {onClearFilters && (
        // Stays in place while the list below changes, so a screen reader hears the result.
        <p role="status" className="sr-only">
          {filtered && count !== undefined ? found(count, feed.hasNextPage) : ''}
        </p>
      )}
      <Results scope={scope} feed={feed} onClearFilters={filtered ? onClearFilters : undefined} />
    </>
  );
}

function Results({
  scope,
  feed,
  onClearFilters,
}: {
  scope: EventScope;
  feed: Feed;
  /** Set while a search or filter is on. */
  onClearFilters?: () => void;
}) {
  const { data } = feed;

  if (!data && !feed.isError) return <LoadingCards />;
  if (!data) {
    // The first page failed. A failed "Load more" keeps the pages we have (below).
    return <ErrorState error={feed.error} onRetry={() => void feed.refetch()} />;
  }

  const events = data.pages.flatMap((page) => page.events);
  if (events.length === 0) {
    if (onClearFilters) return <NoMatches onClear={onClearFilters} />;
    if (scope === 'feed') return <EmptyDiscover />;
    return <p className="text-ink-muted">{EMPTY[scope]}</p>;
  }

  // My schedule marks events that run at the same time, across every day loaded so far.
  const overlaps = scope === 'attending' ? findOverlaps(events) : undefined;

  return (
    <div className="space-y-6">
      {groupByDay(events).map((day) => {
        const { weekday, date } = dayParts(day.key);
        return (
          <section key={day.key} aria-labelledby={`day-${day.key}`} className="space-y-3">
            <h2 id={`day-${day.key}`} className="text-[1.0625rem] font-extrabold">
              {weekday} <span className="text-ink-muted font-sans text-sm font-normal">{date}</span>
            </h2>
            <ul className="space-y-3">
              {day.events.map((event) => (
                <EventCard key={event.id} event={event} overlaps={overlaps?.get(event.id)} />
              ))}
            </ul>
          </section>
        );
      })}
      {feed.isFetchNextPageError && (
        <ErrorState error={feed.error} onRetry={() => void feed.fetchNextPage()} />
      )}
      {feed.hasNextPage && (
        <button
          type="button"
          className="btn btn-secondary w-full sm:w-auto"
          disabled={feed.isFetchingNextPage}
          onClick={() => void feed.fetchNextPage()}
        >
          {feed.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </div>
  );
}
