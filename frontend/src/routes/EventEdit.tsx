import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useEvent, useUpdateEvent } from '../api/events';
import { ErrorState } from '../components/ErrorState';
import { useGoBack } from '../components/shell/useGoBack';
import { Skeleton } from '../components/Skeleton';
import { EventForm } from '../features/events/EventForm';
import { EventNotFound } from './EventDetail';

export function EventEdit() {
  const { eventId = '' } = useParams();
  // After a save: back to the event, so Back from there doesn't return to this form.
  const leave = useGoBack(`/events/${eventId}`);
  const event = useEvent(eventId);
  const update = useUpdateEvent(eventId);

  if (event.isPending) return <Skeleton className="h-64 w-full" />;
  if (event.error instanceof ApiError && [400, 404].includes(event.error.status)) {
    return <EventNotFound />;
  }
  if (event.isError) return <ErrorState error={event.error} onRetry={() => void event.refetch()} />;

  const data = event.data;
  const back = `/events/${data.id}`;
  // Only the host can edit (anyone else gets the same "not found" the server would give).
  if (!data.isHost) return <EventNotFound />;
  // A cancelled event can't be edited (409).
  if (data.status === 'CANCELLED') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">This event was cancelled</h1>
        <p>A cancelled event can’t be edited.</p>
        <Link to={back} className="btn btn-secondary">
          Back to the event
        </Link>
      </div>
    );
  }

  return (
    <EventForm
      event={data}
      defaultTimeZone={data.timeZone}
      submitLabel="Save changes"
      pending={update.isPending}
      error={update.error}
      onSubmit={(request) => update.mutate(request, { onSuccess: leave })}
    />
  );
}
