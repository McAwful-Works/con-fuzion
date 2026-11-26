import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import {
  isSearchable,
  USER_QUERY_MAX,
  USER_QUERY_MIN,
  useUserSearch,
  type AdminUser,
} from '../../api/admin';
import { isAdmin, useMe } from '../../api/me';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { useNow } from '../rsvp/signup';
import { BanAction } from './BanAction';
import { Fact, When } from './Fact';
import { RolesAction } from './RolesAction';
import { SuspendAction } from './SuspendAction';

/** The contract's pattern for a Discord user ID. */
const DISCORD_ID = /^[0-9]{17,19}$/;

function SearchForm({ initial, onSearch }: { initial: string; onSearch: (q: string) => void }) {
  const [text, setText] = useState(initial);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (text.trim().length >= USER_QUERY_MIN) onSearch(text.trim());
  };

  return (
    <form role="search" onSubmit={submit} className="space-y-2">
      <label className="block space-y-1">
        <span className="text-sm font-medium">Name, user ID or Discord ID</span>
        <input
          type="search"
          value={text}
          maxLength={USER_QUERY_MAX}
          onChange={(e) => setText(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3"
        />
      </label>
      <button
        type="submit"
        className="btn btn-primary"
        disabled={text.trim().length < USER_QUERY_MIN}
      >
        Search
      </button>
    </form>
  );
}

/** Exactly what D-015 lets a moderator see about a person, and no more. */
function UserCard({ user, now }: { user: AdminUser; now: number }) {
  const name = user.globalName ?? user.username;
  const suspended = user.suspendedUntil && Date.parse(user.suspendedUntil) > now;
  const admin = isAdmin(useMe().data);

  return (
    <li className="border-line bg-surface-raised rounded-card space-y-3 border p-4">
      <h3 className="font-semibold break-words">{name}</h3>
      <dl className="grid gap-2 sm:grid-cols-2">
        <Fact label="Username">{user.username}</Fact>
        <Fact label="Discord ID">{user.discordId}</Fact>
        <Fact label="Joined">
          <When iso={user.createdAt} />
        </Fact>
        <Fact label="Last signed in">
          <When iso={user.lastLoginAt} />
        </Fact>
        <Fact label="Suspension">
          {user.suspendedUntil ? (
            <>
              {suspended ? 'Suspended until ' : 'Ended '}
              <When iso={user.suspendedUntil} />
            </>
          ) : (
            'None'
          )}
        </Fact>
        <Fact label="Ban">
          {user.ban ? (
            <>
              Banned <When iso={user.ban.createdAt} />. Reason: {user.ban.reason}
            </>
          ) : (
            'None'
          )}
        </Fact>
        <Fact label="Reports against them">{user.reportCount}</Fact>
      </dl>
      <SuspendAction userId={user.id} name={name} />
      {admin && (
        <>
          <BanAction discordId={user.discordId} name={name} banned={user.ban !== null} />
          <RolesAction userId={user.id} name={name} />
        </>
      )}
    </li>
  );
}

/** A Discord ID can be banned before it signs up, and stays banned if the account goes (D-015). */
function NoAccount({ discordId }: { discordId: string }) {
  return (
    <div className="space-y-3">
      <p className="text-sm">
        No account here has this Discord ID. You can still ban it, or lift a ban on it.
      </p>
      <BanAction discordId={discordId} name={`Discord ID ${discordId}`} />
    </div>
  );
}

/** Find people by name or ID. The query is in the address, so a report can link straight to one. */
export function UserSearch() {
  const [params, setParams] = useSearchParams();
  const q = (params.get('q') ?? '').trim();
  const search = useUserSearch(q);
  const asked = isSearchable(q);
  const admin = isAdmin(useMe().data);
  // Only "suspended until" against "ended" depends on the time, so a slow clock is enough.
  const now = useNow(30_000);

  return (
    <section aria-labelledby="users-heading" className="space-y-4">
      <h2 id="users-heading" className="text-xl font-semibold">
        Users
      </h2>
      <SearchForm key={q} initial={q} onSearch={(next) => setParams({ q: next })} />
      {/* The address can carry a query the form would never send. */}
      {q !== '' && !asked && (
        <p className="text-ink-muted">
          Search for {USER_QUERY_MIN} to {USER_QUERY_MAX} characters.
        </p>
      )}
      {asked && search.isPending && (
        <div role="status" aria-label="Searching" className="space-y-3">
          <Skeleton className="h-40 w-full" />
        </div>
      )}
      {asked && search.isError && (
        <ErrorState error={search.error} onRetry={() => void search.refetch()} />
      )}
      {asked && search.data?.length === 0 && <p className="text-ink-muted">No one found.</p>}
      {admin && search.data?.length === 0 && DISCORD_ID.test(q) && (
        <NoAccount key={q} discordId={q} />
      )}
      {asked && search.data && search.data.length > 0 && (
        <>
          <p className="text-ink-muted text-sm">
            Up to 20 matches, by username. Narrow the search to find others.
          </p>
          <ul aria-label="Search results" className="space-y-3">
            {search.data.map((user) => (
              <UserCard key={user.id} user={user} now={now} />
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
