import type { ReactNode } from 'react';
import { useAuditLog, type AuditEntry } from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { Fact, When } from './Fact';
import { words } from './labels';

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

function Entry({ entry }: { entry: AuditEntry }) {
  const keys = (Object.keys(DETAIL_LABELS) as (keyof Details)[]).filter(
    (key) => entry.details[key] !== undefined,
  );

  return (
    <li className="border-line bg-surface-raised rounded-card space-y-3 border p-4">
      <h3 className="font-semibold">{words(entry.action)}</h3>
      <dl className="grid gap-2">
        <Fact label="When">
          <When iso={entry.createdAt} />
        </Fact>
        <Fact label="By">{entry.actorId ?? 'The system'}</Fact>
        <Fact label={words(entry.targetType)}>{entry.targetId}</Fact>
        {keys.map((key) => (
          <Fact key={key} label={DETAIL_LABELS[key]}>
            {detailValue(entry.details, key)}
          </Fact>
        ))}
      </dl>
    </li>
  );
}

/** Admin only: every moderator and admin action, newest first (D-015 rule 15). */
export function AuditLog() {
  const log = useAuditLog();
  const entries = log.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section aria-labelledby="audit-heading" className="space-y-4">
      <h2 id="audit-heading" className="text-xl font-semibold">
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
      {entries.length > 0 && (
        <ul aria-label="Audit entries" className="space-y-3">
          {entries.map((entry) => (
            <Entry key={entry.id} entry={entry} />
          ))}
        </ul>
      )}
      {log.isFetchNextPageError && (
        <ErrorState error={log.error} onRetry={() => void log.fetchNextPage()} />
      )}
      {log.hasNextPage && (
        <button
          type="button"
          className="btn btn-secondary w-full sm:w-auto"
          disabled={log.isFetchingNextPage}
          onClick={() => void log.fetchNextPage()}
        >
          {log.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
  );
}
