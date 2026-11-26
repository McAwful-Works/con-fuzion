import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
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
import { NotFound } from '../../components/NotFound';
import { Skeleton } from '../../components/Skeleton';
import { Fact, When } from './Fact';
import { reasonLabel, STATUS_LABELS, targetName } from './labels';
import { ModAction } from './ModAction';
import { SuspendAction } from './SuspendAction';

const link = 'inline-flex min-h-11 items-center underline';

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

  return (
    <>
      <ModAction
        label="Hide event"
        confirm="Hide"
        success="Event hidden."
        mutation={hide}
        note={reason}
        onSubmit={(note, done) => hide.mutate(note, { onSuccess: done })}
      >
        <p className="text-sm">
          Only the host and moderators will be able to open it. People who were going aren’t told.
        </p>
      </ModAction>
      <ModAction
        label="Unhide event"
        confirm="Unhide"
        success="Event unhidden."
        mutation={unhide}
        onSubmit={(_, done) => unhide.mutate(undefined, { onSuccess: done })}
      >
        <p className="text-sm">
          Its audience can open it again. Nobody is told, though any free spots are offered to the
          waitlist.
        </p>
      </ModAction>
      <ModAction
        label="Cancel event"
        confirm="Cancel it"
        success="Event cancelled."
        mutation={cancel}
        note={reason}
        onSubmit={(note, done) => cancel.mutate(note, { onSuccess: done })}
      >
        <p className="text-sm">
          People who were going or waiting are told it’s cancelled, without your reason. This can’t
          be undone. An event that has already ended isn’t changed.
        </p>
      </ModAction>
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
        <legend className="text-sm font-medium">How did it end?</legend>
        {(['ACTIONED', 'DISMISSED'] as const).map((value) => (
          <label key={value} className="flex min-h-11 items-center gap-3">
            <input
              type="radio"
              name="resolution"
              checked={resolution === value}
              onChange={() => setResolution(value)}
              className="size-5 shrink-0"
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
    <article className="space-y-6">
      <Link to="/admin" className={`${link} text-sm`}>
        Back to reports
      </Link>
      <h2 className="text-xl font-semibold">Report {report.id}</h2>
      <dl className="space-y-3">
        <Fact label="Status">{STATUS_LABELS[report.status]}</Fact>
        <Fact label="Reason">{reasonLabel(report.reason)}</Fact>
        <Fact label="Reported">
          {targetName(report)}
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
        <Fact label="Reporter’s note">{report.note ?? 'None'}</Fact>
        {report.handledAt && (
          <>
            <Fact label="Resolved">
              <When iso={report.handledAt} />
              {report.handledBy && (
                <span className="block">
                  <LookUp userId={report.handledBy} label="Look up the moderator" />
                </span>
              )}
            </Fact>
            <Fact label="Resolution note">{report.resolutionNote ?? 'None'}</Fact>
          </>
        )}
      </dl>
      <section aria-labelledby="actions-heading" className="space-y-3">
        <h2 id="actions-heading" className="text-xl font-semibold">
          Actions
        </h2>
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
  // Report IDs are positive whole numbers. Anything else isn't worth asking the server about.
  if (!/^[1-9]\d{0,17}$/.test(reportId)) return <NotFound />;
  return <ReportView key={reportId} reportId={reportId} />;
}
