import { useId } from 'react';
import {
  useGuilds,
  useSetAllGuildsDiscoverable,
  useSetGuildDiscoverable,
  type Guild,
} from '../../api/me';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';

/**
 * The "Make me discoverable here" list, shared by onboarding and Settings → Servers. Everything is
 * off until the user turns it on. Toggles save one server each; "select all" is one bulk request.
 */
export function ServerList() {
  const guilds = useGuilds();
  const bulk = useSetAllGuildsDiscoverable();

  if (guilds.isPending) {
    return (
      <div role="status" aria-label="Loading your servers" className="space-y-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-14 w-full" />
        ))}
      </div>
    );
  }
  if (guilds.isError)
    return <ErrorState error={guilds.error} onRetry={() => void guilds.refetch()} />;

  const list = guilds.data;
  if (list.length === 0) {
    return (
      <p className="text-ink-muted">
        We didn’t find any Discord servers on your account. Join one on Discord, then sign in again
        to see it here.
      </p>
    );
  }

  const allOn = list.every((g) => g.discoverable);
  return (
    <div className="space-y-3">
      <button
        type="button"
        className="btn btn-secondary"
        disabled={bulk.isPending}
        onClick={() => bulk.mutate({ guilds: list, discoverable: !allOn })}
      >
        {allOn ? 'Turn off for all servers' : 'Select all servers'}
      </button>
      {bulk.isError && <ErrorState error={bulk.error} />}
      <ul className="space-y-2">
        {list.map((guild) => (
          <GuildRow key={guild.id} guild={guild} locked={bulk.isPending} />
        ))}
      </ul>
    </div>
  );
}

function GuildRow({ guild, locked }: { guild: Guild; locked: boolean }) {
  const save = useSetGuildDiscoverable(guild.id);
  const id = useId();
  // Show the value being saved so the switch responds at once; the server's value wins afterwards.
  const checked = save.isPending ? save.variables : guild.discoverable;

  return (
    <li className="border-line bg-surface-raised rounded-card border px-3">
      <label htmlFor={id} className="flex min-h-14 cursor-pointer items-center gap-3 py-2">
        {guild.iconUrl ? (
          <img src={guild.iconUrl} alt="" className="size-9 shrink-0 rounded-full" />
        ) : (
          <span
            aria-hidden="true"
            className="bg-line text-ink-muted flex size-9 shrink-0 items-center justify-center rounded-full text-sm font-semibold"
          >
            {guild.name.slice(0, 1).toUpperCase()}
          </span>
        )}
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{guild.name}</span>
          <span className="text-ink-muted block text-sm">Make me discoverable here</span>
        </span>
        <input
          id={id}
          type="checkbox"
          role="switch"
          checked={checked}
          disabled={locked || save.isPending}
          onChange={(event) => save.mutate(event.target.checked)}
          className="accent-accent focus-visible:outline-accent size-6 shrink-0 focus-visible:outline-2 focus-visible:outline-offset-2"
        />
      </label>
      {save.isError && (
        <p role="alert" className="pb-2 text-sm">
          Couldn’t save that change for {guild.name}. Try again.
        </p>
      )}
    </li>
  );
}

/** The plain-language promise shown wherever the list appears. */
export function PrivacyNote() {
  return (
    <p className="text-ink-muted">
      People in this server who use Con-Fuzion can invite you to their meetups. We never see your
      messages or other members. Every server starts off until you turn it on.
    </p>
  );
}
