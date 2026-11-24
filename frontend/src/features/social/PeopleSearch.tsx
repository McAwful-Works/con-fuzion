import { useEffect, useState } from 'react';
import {
  PEOPLE_QUERY_MAX,
  PEOPLE_QUERY_MIN,
  useAddCircleMember,
  usePeopleSearch,
  type Circle,
} from '../../api/social';
import { ErrorState } from '../../components/ErrorState';
import { PersonRow } from './PersonRow';

/** Waits for a pause in typing so each keystroke isn't a request. */
function useDebounced(value: string, ms: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return debounced;
}

/** Search the people you can pick (friends, and people you share a discoverable server with). */
export function PeopleSearch({ circle }: { circle: Circle }) {
  const [text, setText] = useState('');
  const query = useDebounced(text, 250).trim();
  const results = usePeopleSearch(query);
  const add = useAddCircleMember(circle.id);
  const memberIds = new Set(circle.members.map((m) => m.id));
  const tooShort = text.trim().length < PEOPLE_QUERY_MIN;

  return (
    <div className="space-y-3">
      <label className="block space-y-1">
        <span className="text-sm font-medium">Search by name</span>
        <input
          type="search"
          value={text}
          maxLength={PEOPLE_QUERY_MAX}
          onChange={(e) => setText(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3"
        />
      </label>
      <p className="text-ink-muted text-sm">
        Type at least {PEOPLE_QUERY_MIN} characters. You can add friends and people who are in a
        server with you where you’re both discoverable.
      </p>
      {add.isError && (
        <p role="alert" className="text-sm">
          Couldn’t add them. They may not be available to add any more.
        </p>
      )}
      {!tooShort && results.isError && (
        <ErrorState error={results.error} onRetry={() => void results.refetch()} />
      )}
      {!tooShort && results.data && results.data.length === 0 && (
        <p className="text-ink-muted">No one found.</p>
      )}
      {!tooShort && results.data && results.data.length > 0 && (
        <ul aria-label="Search results" className="space-y-2">
          {results.data.map((person) => (
            <PersonRow key={person.id} person={person}>
              {memberIds.has(person.id) ? (
                <span className="text-ink-muted px-2 text-sm">In this circle</span>
              ) : (
                <button
                  type="button"
                  className="btn btn-secondary"
                  aria-label={`Add ${person.displayName}`}
                  disabled={add.isPending}
                  onClick={() => add.mutate(person.id)}
                >
                  Add
                </button>
              )}
            </PersonRow>
          ))}
        </ul>
      )}
    </div>
  );
}
