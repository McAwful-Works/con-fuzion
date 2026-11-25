import { Link } from 'react-router';
import { EventFeed } from '../features/events/EventFeed';

/** My schedule: what you're going to, waiting for or hosting, by day, with clashes marked. */
export function Schedule() {
  return (
    <div className="space-y-6">
      <Link to="/" className="text-sm underline">
        All events
      </Link>
      <h1 className="text-2xl font-semibold">My schedule</h1>
      <EventFeed scope="attending" />
    </div>
  );
}
