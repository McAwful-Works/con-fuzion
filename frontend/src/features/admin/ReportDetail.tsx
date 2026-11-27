import { Check, Lock } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import {
  REASON_MAX,
  RESOLUTION_NOTE_MAX,
  useEventAction,
  useReport,
  useResolveReport,
  type Report,
  type Resolution,
} from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { Fact, When } from './Fact';
import { STATUS_LABELS, targetName } from './labels';
import { ModAction } from './ModAction';
import { ReasonPill } from './ReasonPill';
import { SuspendAction } from './SuspendAction';

const link = 'inline-flex min-h-11 items-center font-bold underline underline-offset-4';

/** User search matches an exact ID, which is how a report's people are looked up (D-015 rule 14). */
function LookUp({ userId, label }: { userId: string; label: string }) {
  return (
    <Link to={`/admin/users?${new URLSearchParams({ q: userId })}`} className={link}>
      {label}
    </Link>
  );
}

function EventActions({ eventId }: { eventId: string }) {
  const hide = useEventAction(eventId, 'hide');
  const unhide = useEventAction(eventId, 'unhide');
  const cancel = useEventAction(eventId, 'cancel');
  const reason = { label: 'Reason (the host reads it)', max: REASON_MAX, required: true };
  // One message for all three, so an earlier "hidden" can't sit beside a later "unhidden".
  const [done, setDone] = useState('');
  const clear = () => setDone('');
  const finish = (close: () => void, message: string) => ({
    onSuccess: () => {
      close();
      setDone(message);
    },
  });

  return (
    <>
      <div className="grid grid-cols-2 gap-3">
        <ModAction
          className="col-span-full"
          primary
          icon={Lock}
          label="Hide event"
          confirm="Hide"
          mutation={hide}
          note={reason}
          onOpen={clear}
          onSubmit={(note, close) => hide.mutate(note, finish(close, 'Event hidden.'))}
        >
          <p className="text-sm">
            Only the host and moderators will be able to open it. People who were going aren’t told.
          </p>
        </ModAction>
        <ModAction
          label="Unhide event"
          confirm="Unhide"
          mutation={unhide}
          onOpen={clear}
          onSubmit={(_, close) => unhide.mutate(undefined, finish(close, 'Event unhidden.'))}
        >
          <p className="text-sm">
            Its audience can open it again. Nobody is told, though any free spots are offered to the
            waitlist.
          </p>
        </ModAction>
        <ModAction
          label="Cancel event"
          confirm="Cancel it"
          mutation={cancel}
          note={reason}
          onOpen={clear}
          onSubmit={(note, close) => cancel.mutate(note, finish(close, 'Event cancelled.'))}
        >
          <p className="text-sm">
            People who were going or waiting are told it’s cancelled, without your reason. This
            can’t be undone. An event that has already ended isn’t changed.
          </p>
        </ModAction>
      </div>
      {/* Always there, so screen readers announce the text when it arrives. */}
      <p role="status" className="text-sm font-medium">
        {done}
      </p>
    </>
  );
}

function Resolve({ report }: { report: Report }) {
  const resolve = useResolveReport(String(report.id));
  const [resolution, setResolution] = useState<Resolution | null>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  // The button that had focus goes away with the open report.
  useEffect(() => {
    if (resolve.isSuccess) doneRef.current?.focus();
  }, [resolve.isSuccess]);

  if (resolve.isSuccess) {
    return (
      <p ref={doneRef} tabIndex={-1} role="status" className="font-medium">
        Report resolved.
      </p>
    );
  }
  // Someone else got there first: say so, now that the report above shows how it ended.
  if (report.status !== 'OPEN') {
    return resolve.isError ? <ErrorState error={resolve.error} /> : null;
  }
  return (
    <ModAction
      icon={Check}
      label="Resolve report"
      confirm="Resolve"
      mutation={resolve}
      onOpen={() => setResolution(null)}
      ready={resolution !== null}
      note={{ label: 'Note for moderators (optional)', max: RESOLUTION_NOTE_MAX }}
      onSubmit={(note, done) =>
        resolve.mutate(
          { resolution: resolution!, resolutionNote: note || null },
          { onSuccess: done },
        )
      }
    >
      <p className="text-sm">
        Act first, then resolve. The reporter is told it was reviewed, and nothing else.
      </p>
      <fieldset>
        <legend className="field-label">How did it end?</legend>
        {(['ACTIONED', 'DISMISSED'] as const).map((value) => (
          <label key={value} className="flex min-h-11 items-center gap-3">
            <input
              type="radio"
              name="resolution"
              checked={resolution === value}
              onChange={() => setResolution(value)}
              className="accent-accent-text size-5 shrink-0"
            />
            <span>{STATUS_LABELS[value]}</span>
          </label>
        ))}
      </fieldset>
    </ModAction>
  );
}

function ReportView({ reportId }: { reportId: string }) {
  const query = useReport(reportId);
  const location = useLocation();
  const headingRef = useRef<HTMLHeadingElement>(null);
  // Chosen from the queue: move to the report, which on desktop is further on than the next row.
  const chosen = (location.state as { select?: boolean } | null)?.select === true;
  const loaded = query.data !== undefined;
  useEffect(() => {
    if (chosen && loaded) headingRef.current?.focus();
  }, [chosen, loaded, location.key]);

  if (query.isPending) {
    return (
      <div role="status" aria-label="Loading the report" className="space-y-3">
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }
  if (!query.data) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }
  const report = query.data;

  return (
    <article className="card space-y-4 p-4 sm:p-5">
      <Link to={{ pathname: '/admin', search: location.search }} className={`${link} text-sm`}>
        Back to reports
      </Link>
      <h2 ref={headingRef} tabIndex={-1} className="text-xl font-extrabold">
        Report {report.id}
      </h2>
      <dl className="grid grid-cols-2 gap-3">
        <Fact label="Status">{STATUS_LABELS[report.status]}</Fact>
        <Fact label="Reason">
          <ReasonPill reason={report.reason} />
        </Fact>
        <Fact label="Reported" className="col-span-full">
          <span className="font-bold">{targetName(report)}</span>
          {report.targetLabel !== null && (
            <span className="block">
              {report.targetType === 'EVENT' ? (
                <Link to={`/events/${report.targetId}`} className={link}>
                  Open event
                </Link>
              ) : (
                <LookUp userId={report.targetId} label="Look up this person" />
              )}
            </span>
          )}
        </Fact>
        <Fact
          label="Reporter’s note"
          className="rounded-control border-control-outline bg-surface-sunken col-span-full border-2 px-3 py-2.5"
        >
          {report.note ?? 'None'}
        </Fact>
        <Fact label="Sent">
          <When iso={report.createdAt} />
        </Fact>
        <Fact label="Reporter">
          {report.reporterId ? (
            <LookUp userId={report.reporterId} label="Look up the reporter" />
          ) : (
            'Account deleted'
          )}
        </Fact>
        {report.handledAt && (
          <>
            <Fact label="Resolved" className="col-span-full">
              <When iso={report.handledAt} />
              {report.handledBy && (
                <span className="block">
                  <LookUp userId={report.handledBy} label="Look up the moderator" />
                </span>
              )}
            </Fact>
            <Fact label="Resolution note" className="col-span-full">
              {report.resolutionNote ?? 'None'}
            </Fact>
          </>
        )}
      </dl>
      <section aria-label="Actions" className="space-y-3">
        {report.targetLabel === null ? (
          <p className="text-ink-muted">What was reported is gone, so there’s only the report.</p>
        ) : report.targetType === 'EVENT' ? (
          <EventActions eventId={report.targetId} />
        ) : (
          <SuspendAction userId={report.targetId} name={report.targetLabel} />
        )}
        <Resolve report={report} />
      </section>
    </article>
  );
}

export function ReportDetail() {
  const { reportId = '' } = useParams();
  return <ReportView key={reportId} reportId={reportId} />;
}
