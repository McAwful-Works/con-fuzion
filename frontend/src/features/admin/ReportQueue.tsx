import { Link, useSearchParams } from 'react-router';
import { useReports, type Report, type ReportStatus } from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { When } from './Fact';
import { reasonLabel, STATUS_LABELS, targetName } from './labels';

const STATUSES = Object.keys(STATUS_LABELS) as ReportStatus[];

function Entry({ report }: { report: Report }) {
  return (
    <li>
      <Link
        to={`/admin/reports/${report.id}`}
        className="border-line bg-surface-raised rounded-card focus-visible:outline-accent block space-y-1 border p-4 focus-visible:outline-2"
      >
        <span className="block font-semibold">{reasonLabel(report.reason)}</span>
        <span className="block break-words">{targetName(report)}</span>
        <span className="text-ink-muted block text-sm">
          Report {report.id}, sent <When iso={report.createdAt} />
        </span>
      </Link>
    </li>
  );
}

/** The queue, filtered by status. The filter is in the address, so going back keeps it. */
export function ReportQueue() {
  const [params] = useSearchParams();
  const status = STATUSES.find((s) => s === params.get('status')) ?? 'OPEN';
  const reports = useReports(status);
  const items = reports.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section aria-labelledby="reports-heading" className="space-y-4">
      <h2 id="reports-heading" className="text-xl font-semibold">
        Reports
      </h2>
      <nav aria-label="Report status" className="flex flex-wrap gap-x-4">
        {STATUSES.map((s) => (
          <Link
            key={s}
            to={`/admin?status=${s}`}
            aria-current={s === status ? 'page' : undefined}
            className={`inline-flex min-h-11 min-w-11 items-center ${
              s === status ? 'font-semibold underline underline-offset-4' : 'text-ink-muted'
            }`}
          >
            {STATUS_LABELS[s]}
          </Link>
        ))}
      </nav>
      {reports.isPending && (
        <div role="status" aria-label="Loading reports" className="space-y-3">
          <Skeleton className="h-24 w-full" />
        </div>
      )}
      {reports.isError && !reports.data && (
        <ErrorState error={reports.error} onRetry={() => void reports.refetch()} />
      )}
      {reports.data && items.length === 0 && (
        <p className="text-ink-muted">No {STATUS_LABELS[status].toLowerCase()} reports.</p>
      )}
      {items.length > 0 && (
        <ul aria-label={`${STATUS_LABELS[status]} reports`} className="space-y-3">
          {items.map((report) => (
            <Entry key={report.id} report={report} />
          ))}
        </ul>
      )}
      {reports.isFetchNextPageError && (
        <ErrorState error={reports.error} onRetry={() => void reports.fetchNextPage()} />
      )}
      {reports.hasNextPage && (
        <button
          type="button"
          className="btn btn-secondary w-full sm:w-auto"
          disabled={reports.isFetchingNextPage}
          onClick={() => void reports.fetchNextPage()}
        >
          {reports.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
  );
}
