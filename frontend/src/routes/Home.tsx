import { useSearchParams } from 'react-router';
import type { EventScope } from '../api/events';
import { useGuilds } from '../api/me';
import { useDesktop } from '../components/shell/useDesktop';
import { SideCards } from '../features/discover/SideCards';
import { sortedDiscoverable } from '../features/discover/servers';
import { EventFeed } from '../features/events/EventFeed';

const TABS: { scope: EventScope; label: string }[] = [
  { scope: 'feed', label: 'Upcoming' },
  { scope: 'hosting', label: 'Hosting' },
];

/** Discover: the meetups you can see, with your offers, plans and servers beside them on desktop. */
export function Home() {
  // In the URL so Back from an event returns to the tab it was opened from.
  const [params, setParams] = useSearchParams();
  const scope: EventScope = params.get('tab') === 'hosting' ? 'hosting' : 'feed';
  const setScope = (next: EventScope) =>
    setParams(next === 'feed' ? {} : { tab: next }, { replace: true });
  const servers = sortedDiscoverable(useGuilds().data ?? []);
  const desktop = useDesktop();
  return (
    <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_17rem] lg:items-start lg:gap-8 xl:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="min-w-0 space-y-4">
        <div>
          {servers.length > 0 && (
            <p className="text-ink-muted truncate text-xs font-bold tracking-wider uppercase">
              {servers.map((guild) => guild.name).join(' · ')}
            </p>
          )}
          <h1 className="text-[1.75rem] leading-tight font-extrabold lg:text-[2.125rem]">
            Discover
          </h1>
        </div>
        <div role="group" aria-label="Which events" className="seg">
          {TABS.map((tab) => (
            <button
              key={tab.scope}
              type="button"
              aria-pressed={scope === tab.scope}
              className="seg-item"
              onClick={() => setScope(tab.scope)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <EventFeed key={scope} scope={scope} />
      </div>
      {desktop && (
        <aside aria-label="Your plans and servers" className="space-y-4">
          <SideCards />
        </aside>
      )}
    </div>
  );
}
