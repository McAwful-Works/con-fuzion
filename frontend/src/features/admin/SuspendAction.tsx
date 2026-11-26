import { useId, useState } from 'react';
import { REASON_MAX, SUSPEND_MAX_DAYS, useSuspend } from '../../api/admin';
import { ModAction } from './ModAction';

/** The local day `days` from today, as a date input writes it (`YYYY-MM-DD`). */
export function localDay(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const two = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** Suspend a person until the start of a day. Suspending again replaces the end (D-015 rule 4). */
export function SuspendAction({ userId, name }: { userId: string; name: string }) {
  const suspend = useSuspend(userId);
  const [date, setDate] = useState('');
  const dateId = useId();
  // Tomorrow at the earliest, since the end must be in the future. The last day is one short of the
  // limit, so a daylight-saving change can't push its midnight past it.
  const first = localDay(1);
  const last = localDay(SUSPEND_MAX_DAYS - 1);

  return (
    <ModAction
      label={`Suspend ${name}`}
      confirm="Suspend"
      success={`${name} is suspended.`}
      mutation={suspend}
      onOpen={() => setDate('')}
      ready={date >= first && date <= last}
      note={{ label: 'Reason (the person never sees it)', max: REASON_MAX, required: true }}
      onSubmit={(reason, done) =>
        // A date and time with no offset is read as local time.
        suspend.mutate(
          { until: new Date(`${date}T00:00`).toISOString(), reason },
          { onSuccess: done },
        )
      }
    >
      <p className="text-sm">
        They’re signed out and can’t sign in until then. Their events and RSVPs stay as they are.
      </p>
      <div className="space-y-1">
        <label htmlFor={dateId} className="block text-sm font-medium">
          Suspended until
        </label>
        <input
          id={dateId}
          type="date"
          value={date}
          min={first}
          max={last}
          required
          onChange={(e) => setDate(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3 sm:w-auto"
        />
        <p className="text-ink-muted text-sm">
          It ends at the start of that day, your time. Suspensions last less than a year.
        </p>
      </div>
    </ModAction>
  );
}
