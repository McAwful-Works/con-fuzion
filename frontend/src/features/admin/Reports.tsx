import { Outlet, useParams } from 'react-router';
import { NotFound } from '../../components/NotFound';
import { useDesktop } from '../../components/shell/useDesktop';
import { ReportQueue } from './ReportQueue';

export function NoReportSelected() {
  return <p className="card text-ink-muted p-5">Choose a report to see it here.</p>;
}

/**
 * The queue and the selected report: side by side on desktop, so a moderator can work through the
 * queue without changing pages. Phones show one or the other.
 */
export function Reports() {
  const desktop = useDesktop();
  const { reportId } = useParams();
  // Report IDs are positive whole numbers. Anything else isn't worth asking the server about.
  if (reportId !== undefined && !/^[1-9]\d{0,17}$/.test(reportId)) return <NotFound />;
  const selected = reportId !== undefined;

  return (
    <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_18rem] lg:items-start lg:gap-4 xl:grid-cols-[minmax(0,1fr)_23.75rem] xl:gap-6">
      {(desktop || !selected) && <ReportQueue selected={reportId} />}
      {(desktop || selected) && (
        // It stays in view while the queue scrolls. The padding keeps shadows and focus rings from
        // being cut off.
        <div className="min-w-0 lg:sticky lg:top-6 lg:-m-1.5 lg:max-h-[calc(100dvh-3rem)] lg:overflow-y-auto lg:p-1.5">
          <Outlet />
        </div>
      )}
    </div>
  );
}
