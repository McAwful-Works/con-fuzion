import { useState, type FormEvent, type ReactNode } from 'react';
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
import { useDesktop } from '../../components/shell/useDesktop';
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
    <form role="search" onSubmit={submit} className="flex flex-col gap-3 sm:flex-row sm:items-end">
      <label className="field sm:max-w-md sm:flex-1">
        <span className="field-label">Name, user ID or Discord ID</span>
        <input
          type="search"
          value={text}
          maxLength={USER_QUERY_MAX}
          onChange={(e) => setText(e.target.value)}
          className="field-control"
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

const nameOf = (user: AdminUser) => user.globalName ?? user.username;

/**
 * With `Identity`, exactly what D-015 lets a moderator see about a person, and no more. A column of
 * the table, or a fact on a card.
 */
const RECORD: {
  label: string;
  width: string;
  value: (user: AdminUser, now: number) => ReactNode;
}[] = [
  { label: 'Joined', width: 'w-[13%]', value: (user) => <When iso={user.createdAt} /> },
  { label: 'Last signed in', width: 'w-[13%]', value: (user) => <When iso={user.lastLoginAt} /> },
  {
    label: 'Suspension',
    width: 'w-[18%]',
    value: ({ suspendedUntil }, now) =>
      suspendedUntil ? (
        <>
          {Date.parse(suspendedUntil) > now ? 'Suspended until ' : 'Ended '}
          <When iso={suspendedUntil} />
        </>
      ) : (
        'None'
      ),
  },
  {
    label: 'Ban',
    width: 'w-[18%]',
    value: ({ ban }) =>
      ban ? (
        <>
          Banned <When iso={ban.createdAt} />. Reason: {ban.reason}
        </>
      ) : (
        'None'
      ),
  },
  { label: 'Reports against them', width: 'w-[11%]', value: (user) => user.reportCount },
];

/** For inside a `<dl>`. */
function Identity({ user }: { user: AdminUser }) {
  return (
    <>
      <Fact label="Username">{user.username}</Fact>
      <Fact label="Discord ID">{user.discordId}</Fact>
    </>
  );
}

/** Suspending, and for an admin bans and roles. */
function UserActions({ user, className }: { user: AdminUser; className: string }) {
  const admin = isAdmin(useMe().data);
  const name = nameOf(user);
  return (
    <div className={className}>
      <SuspendAction userId={user.id} name={name} />
      {admin && (
        <>
          <BanAction discordId={user.discordId} name={name} banned={user.ban !== null} />
          <RolesAction userId={user.id} name={name} />
        </>
      )}
    </div>
  );
}

interface ResultsProps {
  users: AdminUser[];
  now: number;
}

/**
 * Each person is a row group: a row of facts, and under it their actions. Crossing the desktop width
 * swaps this for the cards, which closes an open form.
 */
function UserTable({ users, now }: ResultsProps) {
  const cell = 'px-2 py-3 wrap-anywhere xl:px-4';
  return (
    <div className="card overflow-hidden">
      <table aria-label="Search results" className="w-full table-fixed text-left text-sm">
        <thead className="text-ink-muted text-xs uppercase xl:tracking-wider">
          <tr>
            <th scope="col" className="w-[27%] px-2 py-3 xl:px-4">
              Name
            </th>
            {RECORD.map(({ label, width }) => (
              <th key={label} scope="col" className={`px-2 py-3 xl:px-4 ${width}`}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        {users.map((user) => (
          <tbody key={user.id} className="border-line border-t-2 align-top">
            <tr>
              <th scope="rowgroup" className={`${cell} font-normal`}>
                <h3 className="text-base font-extrabold">{nameOf(user)}</h3>
                <dl className="mt-1 space-y-1">
                  <Identity user={user} />
                </dl>
              </th>
              {RECORD.map(({ label, value }) => (
                <td key={label} className={cell}>
                  {value(user, now)}
                </td>
              ))}
            </tr>
            <tr>
              <td colSpan={RECORD.length + 1} className="px-2 pb-2 xl:px-4">
                {/* Side by side, until one opens its form. */}
                <UserActions
                  user={user}
                  className="grid grid-cols-3 items-start gap-x-3 *:has-[form]:col-span-full *:has-[form]:max-w-xl"
                />
              </td>
            </tr>
          </tbody>
        ))}
      </table>
    </div>
  );
}

function UserCards({ users, now }: ResultsProps) {
  return (
    <ul aria-label="Search results" className="space-y-3">
      {users.map((user) => (
        <li key={user.id} className="card space-y-4 p-4 sm:p-5">
          <h3 className="text-lg font-extrabold wrap-anywhere">{nameOf(user)}</h3>
          <dl className="grid gap-3 sm:grid-cols-2">
            <Identity user={user} />
            {RECORD.map(({ label, value }) => (
              <Fact key={label} label={label}>
                {value(user, now)}
              </Fact>
            ))}
          </dl>
          {/* The buttons fill their column, which would be too wide across a tablet's card. */}
          <UserActions
            user={user}
            className="border-line max-w-md space-y-1 border-t border-dashed pt-4"
          />
        </li>
      ))}
    </ul>
  );
}

/** A Discord ID can be banned before it signs up, and stays banned if the account goes (D-015). */
function NoAccount({ discordId }: { discordId: string }) {
  return (
    <div className="card max-w-md space-y-3 p-4 sm:p-5">
      <p className="text-sm">
        No account here has this Discord ID. You can still ban it, or lift a ban on it.
      </p>
      <BanAction discordId={discordId} name={`Discord ID ${discordId}`} />
    </div>
  );
}

/** Find people by name or ID. The query is in the address, so a report can link straight to one. */
export function UserSearch() {
  const desktop = useDesktop();
  const [params, setParams] = useSearchParams();
  const q = (params.get('q') ?? '').trim();
  const search = useUserSearch(q);
  const asked = isSearchable(q);
  const admin = isAdmin(useMe().data);
  // Only "suspended until" against "ended" depends on the time, so a slow clock is enough.
  const now = useNow(30_000);
  const Results = desktop ? UserTable : UserCards;

  return (
    <section aria-labelledby="users-heading" className="space-y-4">
      <h2 id="users-heading" className="sr-only">
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
          <Results users={search.data} now={now} />
        </>
      )}
    </section>
  );
}
