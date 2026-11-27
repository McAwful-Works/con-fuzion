import { useIsMutating } from '@tanstack/react-query';
import { useId } from 'react';
import {
  useGuilds,
  useSetAllGuildsDiscoverable,
  useSetGuildDiscoverable,
  singleGuildKey,
  type Guild,
} from '../../api/me';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';

/**
 * The "Make me discoverable here" list, shared by the first-run setup and You → Servers. Everything is
 * off until the user turns it on. Toggles save one server each; "Turn on all" is one bulk request.
 */
export function ServerList() {
  const guilds = useGuilds();
  const bulk = useSetAllGuildsDiscoverable();
  // Don't let "Turn on all" race a toggle that is still saving.
  const savingOne = useIsMutating({ mutationKey: singleGuildKey }) > 0;
  const allId = useId();

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

  // The API sorts case-sensitively, which pushes lowercase and emoji names last.
  const list = [...guilds.data].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
  );
  if (list.length === 0) {
    return (
      <p className="text-ink-muted">
        We didn’t find any Discord servers on your account. Join one on Discord, then sign in again
        to see it here.
      </p>
    );
  }

  const allOn = list.every((g) => g.discoverable);
  // Like a row, show the value being saved.
  const allChecked = bulk.isPending ? bulk.variables.discoverable : allOn;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-x-3">
        <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">
          Your servers · {list.length}
        </p>
        <label className="ml-auto flex min-h-11 cursor-pointer items-center gap-2.5 pr-3.5 text-sm font-bold">
          <span id={allId}>Turn on all</span>
          <button
            type="button"
            role="switch"
            aria-checked={allChecked}
            aria-labelledby={allId}
            className="switch"
            disabled={bulk.isPending || savingOne}
            onClick={() => bulk.mutate({ guilds: list, discoverable: !allOn })}
          />
        </label>
      </div>
      {bulk.isError && <ErrorState error={bulk.error} />}
      <ul aria-label="Your servers" className="card divide-line divide-y-2 divide-dashed">
        {list.map((guild) => (
          <GuildRow key={guild.id} guild={guild} locked={bulk.isPending} />
        ))}
      </ul>
    </div>
  );
}

function GuildRow({ guild, locked }: { guild: Guild; locked: boolean }) {
  const save = useSetGuildDiscoverable(guild.id);
  const nameId = useId();
  const purposeId = useId();
  // Show the value being saved so the switch responds at once; the server's value wins afterwards.
  const checked = save.isPending ? save.variables : guild.discoverable;

  return (
    <li className="px-3.5">
      {/* A label, so that a tap anywhere on the row works the switch. */}
      <label className="flex min-h-16 cursor-pointer items-center gap-3 py-2.5">
        {guild.iconUrl ? (
          <img
            src={guild.iconUrl}
            alt=""
            className="rounded-control border-outline size-10 shrink-0 border-2"
          />
        ) : (
          <span
            aria-hidden="true"
            className="rounded-control border-outline bg-surface-sunken flex size-10 shrink-0 items-center justify-center border-2 text-sm font-bold"
          >
            {/* By code point: half of an emoji doesn't draw. */}
            {Array.from(guild.name)[0]?.toUpperCase()}
          </span>
        )}
        <span className="min-w-0 flex-1">
          <span id={nameId} className="block font-bold break-words">
            {guild.name}
          </span>
          {/* For the eye: the switch already says whether it's on. */}
          <span aria-hidden="true" className="text-ink-muted block text-[0.8125rem]">
            {checked ? 'Discoverable' : 'Hidden'}
          </span>
        </span>
        <span id={purposeId} className="sr-only">
          Make me discoverable here
        </span>
        <button
          type="button"
          role="switch"
          aria-checked={checked}
          aria-labelledby={`${nameId} ${purposeId}`}
          disabled={locked || save.isPending}
          onClick={() => save.mutate(!checked)}
          className="switch"
        />
      </label>
      {save.isError && (
        <p role="alert" className="pb-2.5 text-sm">
          Couldn’t save that change for {guild.name}. Try again.
        </p>
      )}
    </li>
  );
}
