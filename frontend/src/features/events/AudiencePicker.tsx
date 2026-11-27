import { Link } from 'react-router';
import { useGuilds } from '../../api/me';
import { AUDIENCE_MAX, type AudienceEntry } from '../../api/events';
import { useCircles } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';
import { PeopleSearch } from '../social/PeopleSearch';
import { circleEntry, entryKey, entryLabel, friendsEntry, guildEntry, userEntry } from './audience';

interface Props {
  value: AudienceEntry[];
  onChange: (next: AudienceEntry[]) => void;
}

function Checkbox({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex min-h-11 items-center gap-3">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="size-5 shrink-0"
      />
      <span className="min-w-0 break-words">{label}</span>
    </label>
  );
}

/**
 * Who can see the event: friends, the servers the host is discoverable in, their circles and
 * individuals found through people search (D-012). At least 1 entry and at most 50.
 */
export function AudiencePicker({ value, onChange }: Props) {
  // Only servers where the host is discoverable can be added (D-012).
  const guilds = useGuilds();
  const circles = useCircles();
  const keys = new Set(value.map(entryKey));
  const full = value.length >= AUDIENCE_MAX;

  const add = (entry: AudienceEntry) => {
    if (!keys.has(entryKey(entry)) && !full) onChange([...value, entry]);
  };
  const remove = (key: string) => onChange(value.filter((e) => entryKey(e) !== key));
  const toggle = (entry: AudienceEntry, on: boolean) => (on ? add(entry) : remove(entryKey(entry)));

  const discoverable = (guilds.data ?? []).filter((g) => g.discoverable);
  const discoverableIds = new Set(discoverable.map((g) => g.id));
  const circleIds = new Set((circles.data ?? []).map((c) => c.id));
  // Entries with no checkbox: individuals, and servers or circles that aren't in the lists above.
  const others = value.filter(
    (e) =>
      e.type === 'USER' ||
      (e.type === 'GUILD' && !guilds.isPending && !discoverableIds.has(e.guildId ?? '')) ||
      (e.type === 'CIRCLE' && !circles.isPending && !circleIds.has(e.circleId ?? '')),
  );
  const chosenPeople = new Set(value.filter((e) => e.type === 'USER').map((e) => e.userId ?? ''));

  return (
    <fieldset className="space-y-6">
      <legend className="text-xl font-semibold">Who can see this</legend>

      <section aria-labelledby="aud-friends" className="space-y-1">
        <h3 id="aud-friends" className="font-medium">
          Friends
        </h3>
        <Checkbox
          label="All my friends"
          checked={keys.has('FRIENDS')}
          disabled={full && !keys.has('FRIENDS')}
          onChange={(on) => toggle(friendsEntry(), on)}
        />
      </section>

      <section aria-labelledby="aud-servers" className="space-y-1">
        <h3 id="aud-servers" className="font-medium">
          My servers
        </h3>
        <p className="text-ink-muted text-sm">
          Only members who opted in to being discoverable in that server will see the event. You’re
          shown to them as a member there.
        </p>
        {guilds.isPending && <p className="text-ink-muted">Loading your servers…</p>}
        {guilds.isError && (
          <ErrorState error={guilds.error} onRetry={() => void guilds.refetch()} />
        )}
        {guilds.data && discoverable.length === 0 && (
          <p className="text-ink-muted">
            You’re not discoverable in any server yet.{' '}
            <Link to="/onboarding" className="underline">
              Choose where you’re discoverable
            </Link>
          </p>
        )}
        {discoverable.map((g) => (
          <Checkbox
            key={g.id}
            label={g.name}
            checked={keys.has(`GUILD:${g.id}`)}
            disabled={full && !keys.has(`GUILD:${g.id}`)}
            onChange={(on) => toggle(guildEntry(g.id, g.name), on)}
          />
        ))}
      </section>

      <section aria-labelledby="aud-circles" className="space-y-1">
        <h3 id="aud-circles" className="font-medium">
          Circles
        </h3>
        {circles.isPending && <p className="text-ink-muted">Loading your circles…</p>}
        {circles.isError && (
          <ErrorState error={circles.error} onRetry={() => void circles.refetch()} />
        )}
        {circles.data && circles.data.length === 0 && (
          <p className="text-ink-muted">
            You don’t have any circles yet.{' '}
            <Link to="/circles" className="underline">
              Make a circle
            </Link>
          </p>
        )}
        {(circles.data ?? []).map((c) => (
          <Checkbox
            key={c.id}
            label={c.name}
            checked={keys.has(`CIRCLE:${c.id}`)}
            disabled={full && !keys.has(`CIRCLE:${c.id}`)}
            onChange={(on) => toggle(circleEntry(c.id, c.name), on)}
          />
        ))}
      </section>

      <section aria-labelledby="aud-people" className="space-y-3">
        <h3 id="aud-people" className="font-medium">
          Individuals
        </h3>
        {full ? (
          <p className="text-ink-muted text-sm">
            You’ve reached the limit of {AUDIENCE_MAX} audience entries. Remove one to add more.
          </p>
        ) : (
          <PeopleSearch
            chosenIds={chosenPeople}
            chosenLabel="Added"
            onPick={(person) => add(userEntry(person.id, person.displayName))}
          />
        )}
      </section>

      {others.length > 0 && (
        <section aria-labelledby="aud-chosen" className="space-y-2">
          <h3 id="aud-chosen" className="font-medium">
            Also included
          </h3>
          <ul className="space-y-2">
            {others.map((e) => (
              <li
                key={entryKey(e)}
                className="border-line bg-surface-raised rounded-card flex flex-wrap items-center gap-3 border p-3"
              >
                <span className="min-w-0 flex-1 basis-40 break-words">{entryLabel(e)}</span>
                <button
                  type="button"
                  className="btn btn-secondary"
                  aria-label={`Remove ${entryLabel(e)}`}
                  onClick={() => remove(entryKey(e))}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </fieldset>
  );
}
