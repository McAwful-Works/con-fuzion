import { useNavigate } from 'react-router';
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
    <EventForm
      defaultTimeZone={defaultTimeZone}
      submitLabel="Create meetup"
      pending={create.isPending}
      error={create.error}
      onSubmit={(request) =>
        create.mutate(request, {
          // Replaced, so Back from the new event doesn't return to the empty form.
          onSuccess: (event) => void navigate(`/events/${event.id}`, { replace: true }),
        })
      }
    />
  );
}
