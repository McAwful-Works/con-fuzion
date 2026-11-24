import { useEventFeed, type EventScope } from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { EventCard } from './EventCard';
import { groupByDay } from './time';

const EMPTY: Record<EventScope, string> = {
  feed: 'No upcoming events yet. Events from your friends, circles and servers will show up here, or host one yourself.',
  hosting:
    'You’re not hosting anything yet. Events you create show up here, including cancelled ones.',
};

/** Upcoming events grouped by day in each event's own time zone, loaded a page at a time. */
export function EventFeed({ scope }: { scope: EventScope }) {
  const feed = useEventFeed(scope);

  const { data } = feed;

  if (!data && !feed.isError) {
    return (
      <div role="status" aria-label="Loading events" className="space-y-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-28 w-full" />
        ))}
      </div>
    );
  }
  if (!data) {
    // The first page failed. A failed "Load more" keeps the pages we have (below).
    return <ErrorState error={feed.error} onRetry={() => void feed.refetch()} />;
  }

  const events = data.pages.flatMap((page) => page.events);
  if (events.length === 0) return <p className="text-ink-muted">{EMPTY[scope]}</p>;

  return (
    <div className="space-y-6">
      {groupByDay(events).map((day) => (
        <section key={day.key} aria-labelledby={`day-${day.key}`} className="space-y-3">
          <h2 id={`day-${day.key}`} className="text-ink-muted text-sm font-semibold uppercase">
            {day.heading}
          </h2>
          <ul className="space-y-3">
            {day.events.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </ul>
        </section>
      ))}
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
