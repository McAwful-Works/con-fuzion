import { Link, useSearchParams } from 'react-router';
import type { EventScope } from '../api/events';
import { EventFeed } from '../features/events/EventFeed';

const TABS: { scope: EventScope; label: string }[] = [
  { scope: 'feed', label: 'Upcoming' },
  { scope: 'hosting', label: 'Hosting' },
];

export function Home() {
  // In the URL so Back from an event returns to the tab it was opened from.
  const [params, setParams] = useSearchParams();
  const scope: EventScope = params.get('tab') === 'hosting' ? 'hosting' : 'feed';
  const setScope = (next: EventScope) =>
    setParams(next === 'feed' ? {} : { tab: next }, { replace: true });
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">Events</h1>
        <Link to="/events/new" className="btn btn-primary">
          Create an event
        </Link>
      </div>
      <div role="group" aria-label="Which events" className="flex gap-2">
        {TABS.map((tab) => (
          <button
            key={tab.scope}
            type="button"
            aria-pressed={scope === tab.scope}
            className={`btn ${scope === tab.scope ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => setScope(tab.scope)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <EventFeed key={scope} scope={scope} />
      <nav aria-label="More" className="flex flex-col gap-3 sm:flex-row">
        <Link to="/friends" className="btn btn-secondary">
          Friends
        </Link>
        <Link to="/circles" className="btn btn-secondary">
          Circles
        </Link>
        <Link to="/onboarding" className="btn btn-secondary">
          Choose where you’re discoverable
        </Link>
      </nav>
    </div>
  );
}
