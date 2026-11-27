import { useRef } from 'react';
import { useSearchParams } from 'react-router';
import type { EventScope } from '../api/events';
import { useDesktop } from '../components/shell/useDesktop';
import { FilterBar } from '../features/discover/FilterBar';
import { SideCards } from '../features/discover/SideCards';
import { useFeedFilters } from '../features/discover/useFeedFilters';
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
  const search = useFeedFilters();
  const allChip = useRef<HTMLButtonElement>(null);
  const { servers } = search;
  const desktop = useDesktop();
  return (
    // `data-discover-page` makes the shell give this page more room. (`data-discover` is React Router's
    // own, on every link.)
    <div
      data-discover-page
      className="lg:grid lg:grid-cols-[minmax(0,1fr)_17rem] lg:items-start lg:gap-8 xl:grid-cols-[minmax(0,1fr)_20rem]"
    >
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
        {scope === 'feed' ? (
          <>
            <FilterBar {...search} allRef={allChip} />
            <EventFeed
              key="feed"
              scope="feed"
              filters={search.filters}
              waiting={search.waiting}
              onClearFilters={() => {
                search.clear();
                // The button pressed is about to go; don't drop the keyboard to the top of the page.
                allChip.current?.focus();
              }}
            />
          </>
        ) : (
          // The API takes no search or filters here.
          <EventFeed key={scope} scope={scope} />
        )}
      </div>
      {desktop && (
        <aside aria-label="Your plans and servers" className="space-y-4">
          <SideCards />
        </aside>
      )}
    </div>
  );
}
