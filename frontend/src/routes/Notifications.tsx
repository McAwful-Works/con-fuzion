import { Check } from 'lucide-react';
import {
  formatUnread,
  useClearUnreadDots,
  useMarkRead,
  useNotifications,
} from '../api/notifications';
import { ErrorState } from '../components/ErrorState';
import { NotificationList } from '../features/notifications/NotificationList';

/** `/inbox`. Opening it already tells the server what's read; "Mark all read" clears the dots. */
export function Notifications() {
  const inbox = useNotifications();
  const markRead = useMarkRead();
  const clearDots = useClearUnreadDots();
  const items = inbox.data?.pages.flatMap((page) => page.items) ?? [];
  const anyUnread = items.some((n) => !n.read);
  // The server's count from before this visit marked anything.
  const unread = inbox.data?.pages[0]?.unreadCount ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          {unread > 0 && (
            <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">
              {formatUnread(unread)} unread
            </p>
          )}
          <h1 className="text-[1.75rem] leading-tight font-extrabold lg:text-[2.125rem]">Inbox</h1>
        </div>
        {items.length > 0 && (
          <button
            type="button"
            className="btn btn-ghost btn-sm aria-disabled:opacity-50"
            // Not `disabled`, which would drop the keyboard to the top of the page once pressed.
            aria-disabled={!anyUnread || markRead.isPending}
            onClick={() => {
              if (!anyUnread || markRead.isPending) return;
              markRead.mutate(Math.max(...items.map((n) => n.id)), { onSuccess: clearDots });
            }}
          >
            <Check aria-hidden="true" className="size-4" />
            Mark all read
          </button>
        )}
      </div>
      {/* Always here, so that a screen reader hears it when it fills. */}
      <p role="status" className="sr-only">
        {markRead.isSuccess && !anyUnread ? 'All marked as read.' : ''}
      </p>
      {markRead.isError && <ErrorState error={markRead.error} />}
      <NotificationList />
    </div>
  );
}
