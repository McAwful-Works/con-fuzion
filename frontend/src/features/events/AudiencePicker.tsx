import { Check, Users, UsersRound, X } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useGuilds } from '../../api/me';
import { AUDIENCE_MAX, type AudienceEntry } from '../../api/events';
import { useCircles } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';
import { ServerIcon } from '../servers/ServerIcon';
import { PeopleSearch } from '../social/PeopleSearch';
import { circleEntry, entryKey, entryLabel, friendsEntry, guildEntry, userEntry } from './audience';

interface Props {
  value: AudienceEntry[];
  onChange: (next: AudienceEntry[]) => void;
}

const ICON = 'rounded-control flex size-10 shrink-0 items-center justify-center';

/** A card that is one checkbox: tapping anywhere on it picks it. */
function OptionCard({
  icon,
  title,
  sub,
  checked,
  disabled,
  onChange,
}: {
  icon: ReactNode;
  title: string;
  sub: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <label className="card has-checked:border-accent has-checked:shadow-picked flex cursor-pointer items-center gap-3 p-3.5 has-disabled:cursor-default has-disabled:opacity-50">
      {icon}
      <span className="min-w-0 flex-1">
        <span id={`${id}-title`} className="block font-bold break-words">
          {title}
        </span>
        <span id={`${id}-sub`} className="hint block">
          {sub}
        </span>
      </span>
      <span className="relative flex shrink-0">
        <input
          type="checkbox"
          aria-labelledby={`${id}-title`}
          aria-describedby={`${id}-sub`}
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="peer border-control-outline bg-surface-raised checked:bg-accent rounded-tag size-6.5 appearance-none border-2"
        />
        <Check
          aria-hidden="true"
          className="text-accent-ink pointer-events-none absolute inset-0 m-auto hidden size-4 peer-checked:block"
        />
      </span>
    </label>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="space-y-2.5">
      <h3 id={id} className="text-ink-muted text-xs font-extrabold tracking-wider uppercase">
        {title}
      </h3>
      {children}
    </section>
  );
}

/** Chosen entries that have no card, each a chip that removes itself. */
function Chips({
  label,
  entries,
  onRemove,
}: {
  label: string;
  entries: AudienceEntry[];
  onRemove: (key: string) => void;
}) {
  if (entries.length === 0) return null;
  return (
    <ul aria-label={label} className="flex flex-wrap gap-2">
      {entries.map((e) => (
        <li key={entryKey(e)} className="max-w-full">
          <button
            type="button"
            className="chip max-w-full py-1 text-left whitespace-normal"
            aria-label={`Remove ${entryLabel(e)}`}
            onClick={() => onRemove(entryKey(e))}
          >
            <span className="min-w-0 break-words">{entryLabel(e)}</span>
            <X aria-hidden="true" className="size-4 shrink-0" />
          </button>
        </li>
      ))}
    </ul>
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
  /** The checkbox half of a card's props. */
  const toggle = (entry: AudienceEntry) => {
    const checked = keys.has(entryKey(entry));
    return {
      checked,
      disabled: full && !checked,
      onChange: (on: boolean) => (on ? add(entry) : remove(entryKey(entry))),
    };
  };

  const discoverable = (guilds.data ?? []).filter((g) => g.discoverable);
  const discoverableIds = new Set(discoverable.map((g) => g.id));
  const circleIds = new Set((circles.data ?? []).map((c) => c.id));
  const people = value.filter((e) => e.type === 'USER');
  // Servers and circles on the event that aren't in the lists above, so they have no card.
  const others = value.filter(
    (e) =>
      (e.type === 'GUILD' && !guilds.isPending && !discoverableIds.has(e.guildId ?? '')) ||
      (e.type === 'CIRCLE' && !circles.isPending && !circleIds.has(e.circleId ?? '')),
  );
  const chosenPeople = new Set(people.map((e) => e.userId ?? ''));

  return (
    <div className="space-y-6">
      <OptionCard
        icon={
          <span className={`${ICON} bg-hosting text-hosting-ink`}>
            <Users aria-hidden="true" className="size-5" />
          </span>
        }
        title="All my friends"
        sub="Everyone on your friends list"
        {...toggle(friendsEntry())}
      />

      <Group title="Servers where you’re discoverable">
        <p className="hint">
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
          <OptionCard
            key={g.id}
            icon={
              <ServerIcon
                name={g.name}
                url={g.iconUrl}
                className={ICON}
                initialClassName="bg-tag text-tag-ink font-extrabold"
              />
            }
            title={g.name}
            sub="Members who are discoverable there"
            {...toggle(guildEntry(g.id, g.name))}
          />
        ))}
      </Group>

      <Group title="Your circles">
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
          <OptionCard
            key={c.id}
            icon={
              <span className={`${ICON} bg-going text-going-ink`}>
                <UsersRound aria-hidden="true" className="size-5" />
              </span>
            }
            title={c.name}
            sub={c.members.length === 1 ? '1 person' : `${c.members.length} people`}
            {...toggle(circleEntry(c.id, c.name))}
          />
        ))}
      </Group>

      <Group title="Specific people">
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
        <Chips label="Added people" entries={people} onRemove={remove} />
      </Group>

      {others.length > 0 && (
        <Group title="Also included">
          <Chips label="Other entries" entries={others} onRemove={remove} />
        </Group>
      )}
    </div>
  );
}
