import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../../api/client';
import { eventQuery } from '../../api/events';
import { useMarkRead, useNotifications, type Notification } from '../../api/notifications';
import { CardList } from '../../components/CardList';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { useNow } from '../rsvp/signup';
import { describeNotification } from './describe';
import { lookOf } from './look';

const WHEN = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

/** Where an entry leads: its event, the friends list for a friend invite, or the admin area for a report. */
function targetOf(n: Notification): string | null {
  if (n.eventId) return `/events/${n.eventId}`;
  if (n.type === 'FRIEND_ACCEPTED') return '/people';
  return n.type === 'REPORT_RECEIVED' ? '/admin' : null;
}

/** An offer whose deadline hasn't passed. It may have been answered since: the event page says. */
function offerOpen(n: Notification, now: number): boolean {
  if (n.type !== 'SPOT_OFFERED') return false;
  const deadline = n.payload.offerExpiresAt;
  return !deadline || Date.parse(deadline) > now;
}

function linkLabel(n: Notification, checking: boolean, offer: boolean): string {
  if (n.eventId) {
    if (checking) return 'Opening…';
    return offer ? 'Review offer' : 'View event';
  }
  return n.type === 'REPORT_RECEIVED' ? 'Open reports' : 'View friends';
}

function Entry({ n, now }: { n: Notification; now: number }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [gone, setGone] = useState(false);
  const [checking, setChecking] = useState(false);
  const target = targetOf(n);

  // An event can be deleted after the notification was made. Look before leaving, and say so here
  // instead of sending the person to a "not found" page. Anything but a 404 just goes on.
  const open = async (e: MouseEvent<HTMLAnchorElement>) => {
    if (!n.eventId || e.defaultPrevented || e.button !== 0) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    if (checking) return;
    setChecking(true);
    try {
      await queryClient.fetchQuery({ ...eventQuery(n.eventId), staleTime: 0 });
    } catch (error) {
      if (error instanceof ApiError && [400, 404].includes(error.status)) {
        setGone(true);
        setChecking(false);
        return;
      }
    }
    setChecking(false);
    void navigate(`/events/${n.eventId}`);
  };

  const offer = offerOpen(n, now);
  const { icon: Icon, tile } = lookOf(n.type);
  return (
    <li className="flex items-start gap-3 px-3.5 py-3.5">
      <span className={`rounded-control flex size-10 shrink-0 items-center justify-center ${tile}`}>
        <Icon aria-hidden="true" className="size-5" />
      </span>
      <div className="min-w-0 flex-1 space-y-1">
        <p className="font-semibold break-words">
          {!n.read && <span className="sr-only">New: </span>}
          {describeNotification(n, now)}
        </p>
        {gone ? (
          <p role="status" className="text-sm font-medium">
            This event is no longer available.
          </p>
        ) : (
          target && (
            <Link
              to={target}
              onClick={(e) => void open(e)}
              className={
                offer
                  ? 'btn btn-primary btn-sm my-1'
                  : 'inline-flex min-h-11 items-center text-sm font-bold underline'
              }
            >
              {linkLabel(n, checking, offer)}
            </Link>
          )
        )}
        <p className="hint">
          <time dateTime={n.createdAt}>{WHEN.format(new Date(n.createdAt))}</time>
        </p>
      </div>
      {/* A shape as well as a color; screen readers get "New" before the words. */}
      {!n.read && (
        <span
          aria-hidden="true"
          data-unread
          className="bg-accent border-outline mt-1.5 size-3 shrink-0 rounded-full border-2"
        />
      )}
    </li>
  );
}

/** Today's entries and the earlier ones, by the day on this device. Empty groups are left out. */
function byDay(items: Notification[], now: number) {
  const today = new Date(now).toDateString();
  const isToday = (n: Notification) => new Date(n.createdAt).toDateString() === today;
  return [
    { id: 'today', title: 'Today', items: items.filter(isToday) },
    { id: 'earlier', title: 'Earlier', items: items.filter((n) => !isToday(n)) },
  ].filter((group) => group.items.length > 0);
}

/** Calls `onVisible` when the element scrolls into view. Without IntersectionObserver, the button does it. */
function useWhenVisible(onVisible: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  const latest = useRef(onVisible);
  useEffect(() => {
    latest.current = onVisible;
  });
  useEffect(() => {
    const el = ref.current;
    if (!enabled || !el || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) latest.current();
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [enabled]);
  return ref;
}

/** The inbox, newest first, loading more as you scroll. Opening it marks what's on screen as read. */
export function NotificationList() {
  const inbox = useNotifications();
  const { mutate: markRead } = useMarkRead();
  const items = inbox.data?.pages.flatMap((page) => page.items) ?? [];
  const newestId = items.reduce((max, n) => Math.max(max, n.id), 0);
  const anyUnread = items.some((n) => !n.read);

  // Mark read up to the newest ID on screen (D-014 rule 11): once per newest ID, again if a newer one shows up.
  const markedUpTo = useRef(0);
  useEffect(() => {
    if (!anyUnread || newestId <= markedUpTo.current) return;
    markedUpTo.current = newestId;
    markRead(newestId, {
      onError: () => {
        markedUpTo.current = 0;
      },
    });
  }, [anyUnread, newestId, markRead]);

  const canLoadMore = inbox.hasNextPage && !inbox.isFetchingNextPage && !inbox.isFetchNextPageError;
  const sentinel = useWhenVisible(() => void inbox.fetchNextPage(), canLoadMore);
  // An offer's deadline and the turn of the day depend on the time, so a slow clock is enough.
  const now = useNow(30_000);

  if (!inbox.data && !inbox.isError) {
    return (
      <div role="status" aria-label="Loading notifications" className="space-y-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-20 w-full" />
        ))}
      </div>
    );
  }
  if (!inbox.data) {
    return <ErrorState error={inbox.error} onRetry={() => void inbox.refetch()} />;
  }
  if (items.length === 0) {
    return (
      <p className="text-ink-muted">
        Nothing yet. You’ll see offers, changes to events you’re going to and reminders here.
      </p>
    );
  }

  return (
    <div className="space-y-4">
      {byDay(items, now).map((group) => (
        <section key={group.id} aria-labelledby={`inbox-${group.id}`} className="space-y-2">
          <h2
            id={`inbox-${group.id}`}
            className="text-ink-muted px-0.5 font-sans text-[0.8125rem] font-extrabold tracking-wider uppercase"
          >
            {group.title}
          </h2>
          <CardList>
            {group.items.map((n) => (
              <Entry key={n.id} n={n} now={now} />
            ))}
          </CardList>
        </section>
      ))}
      {inbox.isFetchNextPageError && (
        <ErrorState error={inbox.error} onRetry={() => void inbox.fetchNextPage()} />
      )}
      {inbox.hasNextPage && (
        <div ref={sentinel}>
          <button
            type="button"
            className="btn btn-secondary w-full sm:w-auto"
            disabled={inbox.isFetchingNextPage}
            onClick={() => void inbox.fetchNextPage()}
          >
            {inbox.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  );
}
