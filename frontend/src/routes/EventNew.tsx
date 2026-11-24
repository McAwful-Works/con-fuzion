import { Link, useNavigate } from 'react-router';
import { useCreateEvent } from '../api/events';
import { useMe } from '../api/me';
import { Skeleton } from '../components/Skeleton';
import { EventForm } from '../features/events/EventForm';
import { browserTimeZone, isValidTimeZone } from '../features/events/time';

export function EventNew() {
  const navigate = useNavigate();
  const me = useMe();
  const create = useCreateEvent();

  // The profile's zone is the default; wait for it so the form doesn't start with the wrong one.
  if (me.isPending) return <Skeleton className="h-64 w-full" />;
  const profileZone = me.data?.timeZone;
  const defaultTimeZone =
    profileZone && isValidTimeZone(profileZone) ? profileZone : browserTimeZone();

  return (
    <div className="space-y-6">
      <Link to="/" className="text-sm underline">
        All events
      </Link>
      <h1 className="text-2xl font-semibold">Create an event</h1>
      <EventForm
        defaultTimeZone={defaultTimeZone}
        submitLabel="Create event"
        pending={create.isPending}
        error={create.error}
        onSubmit={(request) =>
          create.mutate(request, { onSuccess: (event) => void navigate(`/events/${event.id}`) })
        }
      />
    </div>
  );
}
