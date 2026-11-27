import { Plus } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useEventFeed, type Event, type FeedFilters } from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { dayParts } from './cardTime';
import { EventCard } from './EventCard';
import { groupByDay } from './time';

/** Card-shaped blocks while the first page loads. */
export function LoadingCards() {
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

/** Event cards under a heading for each day, the day being the one in each event's own time zone. */
export function EventDays({
  events,
  overlaps,
  after,
}: {
  events: Event[];
  /** The events each one overlaps, by event ID. */
  overlaps?: Map<string, Event[]>;
  /** A list item to put right after an event's card. */
  after?: (event: Event) => ReactNode;
}) {
  return groupByDay(events).map((day) => {
    const { weekday, date } = dayParts(day.key);
    return (
      <section key={day.key} aria-labelledby={`day-${day.key}`} className="space-y-3">
        <h2 id={`day-${day.key}`} className="text-[1.0625rem] font-extrabold">
          {weekday} <span className="text-ink-muted font-sans text-sm font-normal">{date}</span>
        </h2>
        <ul className="space-y-3">
          {day.events.map((event) => (
            <Fragment key={event.id}>
              <EventCard event={event} overlaps={overlaps?.get(event.id)} />
              {after?.(event)}
            </Fragment>
          ))}
        </ul>
      </section>
    );
  });
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
  /** Discover's search and filters. */
  filters?: FeedFilters;
  /** True while the filters aren't known yet: show the placeholders and ask for nothing. */
  waiting?: boolean;
  onClearFilters: () => void;
}

/** Discover's upcoming events grouped by day, loaded a page at a time. */
export function EventFeed({ filters = {}, waiting = false, onClearFilters }: Props) {
  const feed = useEventFeed(filters, !waiting);
  const filtered = Object.keys(filters).length > 0;
  const count = feed.data?.pages.reduce((sum, page) => sum + page.events.length, 0);

  return (
    <>
      {/* Stays in place while the list below changes, so a screen reader hears the result. */}
      <p role="status" className="sr-only">
        {filtered && count !== undefined && !feed.isPlaceholderData
          ? found(count, feed.hasNextPage)
          : ''}
      </p>
      <Results feed={feed} onClearFilters={filtered ? onClearFilters : undefined} />
    </>
  );
}

function Results({
  feed,
  onClearFilters,
}: {
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
    // An empty list from before isn't worth keeping: what it would say may no longer be true.
    if (feed.isPlaceholderData) return <LoadingCards />;
    return onClearFilters ? <NoMatches onClear={onClearFilters} /> : <EmptyDiscover />;
  }

  return (
    <div className="space-y-6" aria-busy={feed.isPlaceholderData}>
      <EventDays events={events} />
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
