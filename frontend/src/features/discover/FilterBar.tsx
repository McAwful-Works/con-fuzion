import { Globe, Search, Users, type LucideIcon } from 'lucide-react';
import { useEffect, useEffectEvent, useState, type Ref } from 'react';
import { SEARCH_MAX } from '../../api/events';
import type { useFeedFilters } from './useFeedFilters';

/** How long typing has to pause before the search is sent. */
const SEARCH_DELAY_MS = 300;

/** The search field. It writes the trimmed text to the URL after a pause in typing, or on Enter. */
function SearchBox({ value, onSearch }: { value: string; onSearch: (q: string) => void }) {
  const [text, setText] = useState(value);
  const [seen, setSeen] = useState(value);
  const [sent, setSent] = useState(value);
  // The URL changed from elsewhere (the Discover link, Clear filters): show what it says. After our
  // own writes the text stays as typed, so a trailing space survives them.
  if (value !== seen) {
    setSeen(value);
    if (value !== sent) {
      setText(value);
      setSent(value);
    }
  }

  function send() {
    setSent(text.trim());
    onSearch(text.trim());
  }
  const sendAfterPause = useEffectEvent(send);
  useEffect(() => {
    if (text.trim() === value) return;
    const id = setTimeout(() => sendAfterPause(), SEARCH_DELAY_MS);
    return () => clearTimeout(id);
  }, [text, value]);

  return (
    <form
      role="search"
      className="relative"
      onSubmit={(event) => {
        event.preventDefault();
        send();
      }}
    >
      <Search
        aria-hidden="true"
        className="text-ink-muted pointer-events-none absolute top-1/2 left-4 size-4.5 -translate-y-1/2"
      />
      <input
        type="search"
        aria-label="Search meetups"
        placeholder="Search meetups"
        enterKeyHint="search"
        maxLength={SEARCH_MAX}
        value={text}
        onChange={(event) => setText(event.target.value)}
        className="field-control shadow-control focus-visible:outline-offset-3 pl-11"
      />
    </form>
  );
}

function Chip({
  pressed,
  icon: Icon,
  label,
  onClick,
  ref,
}: {
  ref?: Ref<HTMLButtonElement>;
  pressed: boolean;
  icon?: LucideIcon;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      ref={ref}
      type="button"
      aria-pressed={pressed}
      className="chip shrink-0"
      onClick={onClick}
    >
      {Icon && <Icon aria-hidden="true" className="size-4 shrink-0" />}
      <span className="max-w-56 truncate">{label}</span>
    </button>
  );
}

/** Discover's search box and filter chips. One server at a time; the other filters combine. */
export function FilterBar({
  filters,
  text,
  servers,
  change,
  allRef,
}: Pick<ReturnType<typeof useFeedFilters>, 'filters' | 'text' | 'servers' | 'change'> & {
  /** The All chip, which takes the focus after Clear filters. */
  allRef: Ref<HTMLButtonElement>;
}) {
  const { guild, friends, hasSpots } = filters;
  return (
    <div className="space-y-2.5">
      <SearchBox value={text} onSearch={(q) => change({ q })} />
      {/*
       * One scrolling row on phones, with no scrollbar (older Safari needs the WebKit rule). The
       * padding keeps focus rings from being cut off.
       */}
      <div
        role="group"
        aria-label="Filters"
        className="scrollbar-none -mx-4 flex gap-2 overflow-x-auto px-4 py-1 lg:mx-0 lg:flex-wrap lg:px-0 [&::-webkit-scrollbar]:hidden"
      >
        <Chip
          ref={allRef}
          label="All"
          pressed={!guild && !friends && !hasSpots}
          onClick={() => change({ guild: undefined, friends: undefined, hasSpots: undefined })}
        />
        {servers.map((server) => (
          <Chip
            key={server.id}
            icon={Globe}
            label={server.name}
            pressed={guild === server.id}
            onClick={() => change({ guild: guild === server.id ? undefined : server.id })}
          />
        ))}
        <Chip
          icon={Users}
          label="Friends"
          pressed={Boolean(friends)}
          onClick={() => change({ friends: friends ? undefined : 'true' })}
        />
        <Chip
          label="Has spots"
          pressed={Boolean(hasSpots)}
          onClick={() => change({ hasSpots: hasSpots ? undefined : 'true' })}
        />
      </div>
    </div>
  );
}
