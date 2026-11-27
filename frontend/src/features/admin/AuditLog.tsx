import type { ReactNode } from 'react';
import { useAuditLog, type AuditEntry } from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { useDesktop } from '../../components/shell/useDesktop';
import { Fact, When } from './Fact';
import { words } from './labels';
import { LoadMore } from './LoadMore';

type Details = AuditEntry['details'];

const DETAIL_LABELS: Record<keyof Details, string> = {
  roles: 'Roles',
  cause: 'Cause',
  resolution: 'Resolution',
  until: 'Until',
  sessions: 'Sessions ended',
  reason: 'Reason',
  note: 'Note',
};

/** Codes are put into words. `reason` and `note` are moderators' own text, shown exactly as written. */
function detailValue(details: Details, key: keyof Details): ReactNode {
  const { roles, cause, resolution, until } = details;
  if (key === 'roles') return roles?.map(words).join(', ') || 'None';
  if (key === 'cause') return words(cause ?? '');
  if (key === 'resolution') return words(resolution ?? '');
  if (key === 'until') return <When iso={until ?? ''} />;
  return details[key];
}

/** The details an entry has, for inside a `<dl>`. */
function DetailFacts({ details }: { details: Details }) {
  return (Object.keys(DETAIL_LABELS) as (keyof Details)[])
    .filter((key) => details[key] !== undefined)
    .map((key) => (
      <Fact key={key} label={DETAIL_LABELS[key]}>
        {detailValue(details, key)}
      </Fact>
    ));
}

const actor = (entry: AuditEntry) => entry.actorId ?? 'The system';

const COLUMNS = [
  ['When', 'w-[16%]'],
  ['Action', 'w-[16%]'],
  ['By', 'w-[20%]'],
  ['Target', 'w-[20%]'],
  ['Details', ''],
] as const;

function AuditTable({ entries }: { entries: AuditEntry[] }) {
  const cell = 'px-3 py-3 wrap-anywhere xl:px-4';
  return (
    <div className="card overflow-hidden">
      <table aria-label="Audit entries" className="w-full table-fixed text-left text-sm">
        <thead className="text-ink-muted border-line border-b-2 text-xs tracking-wider uppercase">
          <tr>
            {COLUMNS.map(([name, width]) => (
              <th key={name} scope="col" className={`px-3 py-3 xl:px-4 ${width}`}>
                {name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="align-top">
          {entries.map((entry) => (
            <tr key={entry.id} className="border-line border-t border-dashed first:border-t-0">
              <td className={cell}>
                <When iso={entry.createdAt} />
              </td>
              <td className={`${cell} font-bold`}>{words(entry.action)}</td>
              <td className={cell}>{actor(entry)}</td>
              <td className={cell}>
                <span className="text-ink-muted block text-xs font-semibold">
                  {words(entry.targetType)}
                </span>
                {entry.targetId}
              </td>
              <td className={cell}>
                <dl className="space-y-2">
                  <DetailFacts details={entry.details} />
                </dl>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AuditCards({ entries }: { entries: AuditEntry[] }) {
  return (
    <ul aria-label="Audit entries" className="space-y-3">
      {entries.map((entry) => (
        <li key={entry.id} className="card space-y-3 p-4">
          <h3 className="font-extrabold">{words(entry.action)}</h3>
          <dl className="grid gap-2 sm:grid-cols-2">
            <Fact label="When">
              <When iso={entry.createdAt} />
            </Fact>
            <Fact label="By">{actor(entry)}</Fact>
            <Fact label={words(entry.targetType)}>{entry.targetId}</Fact>
            <DetailFacts details={entry.details} />
          </dl>
        </li>
      ))}
    </ul>
  );
}

/** Admin only: every moderator and admin action, newest first (D-015 rule 15). */
export function AuditLog() {
  const desktop = useDesktop();
  const log = useAuditLog();
  const entries = log.data?.pages.flatMap((page) => page.items) ?? [];
  const List = desktop ? AuditTable : AuditCards;

  return (
    <section aria-labelledby="audit-heading" className="space-y-4">
      <h2 id="audit-heading" className="sr-only">
        Audit log
      </h2>
      {log.isPending && (
        <div role="status" aria-label="Loading the audit log" className="space-y-3">
          <Skeleton className="h-40 w-full" />
        </div>
      )}
      {log.isError && !log.data && (
        <ErrorState error={log.error} onRetry={() => void log.refetch()} />
      )}
      {log.data && entries.length === 0 && <p className="text-ink-muted">Nothing recorded yet.</p>}
      {entries.length > 0 && <List entries={entries} />}
      <LoadMore query={log} />
    </section>
  );
}
