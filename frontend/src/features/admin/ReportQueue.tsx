import { Link, useSearchParams, type To } from 'react-router';
import { useReports, type Report, type ReportStatus } from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { useDesktop } from '../../components/shell/useDesktop';
import { useNow } from '../rsvp/signup';
import { duration, STATUS_LABELS, targetName } from './labels';
import { ReasonPill } from './ReasonPill';

const STATUSES = Object.keys(STATUS_LABELS) as ReportStatus[];

interface ListProps {
  label: string;
  reports: Report[];
  selected?: string;
  /** Where a report opens, keeping the queue's filter. */
  to: (report: Report) => To;
  /** How long a report has waited, or had waited when it was resolved. */
  waited: (report: Report) => string;
}

/** Tells the report to take focus, so keyboard and screen reader users land on it. */
const SELECT = { select: true };

function ReportTable({ label, reports, selected, to, waited, heading }: ListProps & Heading) {
  return (
    <div className="card overflow-hidden">
      <table aria-label={label} className="w-full text-left text-sm">
        <thead className="text-ink-muted border-line border-b-2 text-xs tracking-wider uppercase">
          <tr>
            <th scope="col" className="w-1/2 px-3 py-3 xl:px-4">
              Reported
            </th>
            <th scope="col" className="px-3 py-3 xl:px-4">
              Reason
            </th>
            <th scope="col" className="px-3 py-3 xl:px-4">
              {heading}
            </th>
          </tr>
        </thead>
        <tbody>
          {reports.map((report) => {
            const current = String(report.id) === selected;
            return (
              <tr
                key={report.id}
                // The whole row follows its link, except for a modified click (meant for the link
                // itself) or the end of a text selection.
                onClick={(e) => {
                  if ((e.target as Element).closest('a')) return;
                  if (e.ctrlKey || e.metaKey || e.shiftKey || window.getSelection()?.toString()) {
                    return;
                  }
                  e.currentTarget.querySelector('a')?.click();
                }}
                className={`border-line cursor-pointer border-t border-dashed first:border-t-0 ${
                  current ? 'bg-surface-sunken' : 'hover:bg-surface'
                }`}
              >
                <td
                  className={`px-1 py-1 xl:px-2 ${
                    current ? 'shadow-[inset_4px_0_0_var(--color-accent-text)]' : ''
                  }`}
                >
                  <Link
                    to={to(report)}
                    state={SELECT}
                    aria-current={current ? 'true' : undefined}
                    className="rounded-control block min-h-11 px-2 py-1.5"
                  >
                    <span className="block font-bold wrap-anywhere">{targetName(report)}</span>
                    <span className="text-ink-muted block text-xs">Report {report.id}</span>
                  </Link>
                </td>
                <td className="px-3 py-2 xl:px-4">
                  <ReasonPill reason={report.reason} />
                </td>
                <td className="px-3 py-2 whitespace-nowrap xl:px-4">{waited(report)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

interface Heading {
  heading: 'Waiting' | 'Waited';
}

function ReportCards({ label, reports, to, waited, heading }: ListProps & Heading) {
  return (
    <ul aria-label={label} className="space-y-3">
      {reports.map((report) => (
        <li key={report.id}>
          <Link to={to(report)} state={SELECT} className="card block space-y-2 p-3.5">
            <span className="flex items-center justify-between gap-3">
              <ReasonPill reason={report.reason} />
              <span className="text-ink-muted shrink-0 text-xs">
                <span className="sr-only">{heading} </span>
                {waited(report)}
              </span>
            </span>
            <span className="block font-bold break-words">{targetName(report)}</span>
            <span className="text-ink-muted block text-xs">Report {report.id}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** The queue, filtered by status. The filter is in the address, so going back keeps it. */
export function ReportQueue({ selected }: { selected?: string }) {
  const desktop = useDesktop();
  const [params] = useSearchParams();
  const status = STATUSES.find((s) => s === params.get('status')) ?? 'OPEN';
  const reports = useReports(status);
  const items = reports.data?.pages.flatMap((page) => page.items) ?? [];
  // Minutes are the smallest unit shown, so a slow clock is enough.
  const now = useNow(30_000);
  const List = desktop ? ReportTable : ReportCards;

  return (
    <section aria-labelledby="reports-heading" className="min-w-0 space-y-4">
      <h2 id="reports-heading" className="sr-only">
        Reports
      </h2>
      <nav aria-label="Report status" className="flex flex-wrap gap-2">
        {STATUSES.map((s) => (
          <Link
            key={s}
            to={`/admin?status=${s}`}
            aria-current={s === status ? 'page' : undefined}
            className="chip"
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
        <List
          label={`${STATUS_LABELS[status]} reports`}
          heading={status === 'OPEN' ? 'Waiting' : 'Waited'}
          reports={items}
          selected={selected}
          to={(report) => ({
            pathname: `/admin/reports/${report.id}`,
            search: status === 'OPEN' ? '' : `?status=${status}`,
          })}
          waited={(report) =>
            duration(
              (report.handledAt ? Date.parse(report.handledAt) : now) -
                Date.parse(report.createdAt),
            )
          }
        />
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
