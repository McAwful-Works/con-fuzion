import { Info } from 'lucide-react';
import { Field } from './formFields';
import { eventTimes, zonedToUtcIso } from './time';
import { TimeZonePicker } from './TimeZonePicker';
import { useViewerZone } from './useViewerZone';
import type { EventFormValues, SectionProps } from './validation';

/** The chosen times in the viewer's own zone, when they read differently there. */
function useYourTime({ startsAt, endsAt, timeZone }: EventFormValues): string | null {
  const viewerZone = useViewerZone();
  const start = zonedToUtcIso(startsAt, timeZone);
  if (!start) return null;
  const end = zonedToUtcIso(endsAt, timeZone);
  const times = { startsAt: start, endsAt: end && end > start ? end : null, timeZone };
  return eventTimes(times, viewerZone).viewer;
}

export function WhenFields({ values, errors, set, isNew }: SectionProps & { isNew: boolean }) {
  const yourTime = useYourTime(values);
  return (
    <>
      <TimeZonePicker
        value={values.timeZone}
        onChange={(zone) => set('timeZone', zone)}
        hint={`Search by city or zone.${isNew ? ' Defaults to the zone you used last.' : ''}`}
        error={errors.timeZone}
      />
      {/* One column again from 1024px until there's room beside the side column. */}
      <div className="grid items-start gap-4 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
        <Field label="Starts" error={errors.startsAt}>
          {(p) => (
            <input
              {...p}
              type="datetime-local"
              value={values.startsAt}
              onChange={(e) => set('startsAt', e.target.value)}
            />
          )}
        </Field>
        <Field label="Ends (optional)" hint="At most 7 days after it starts." error={errors.endsAt}>
          {(p) => (
            <input
              {...p}
              type="datetime-local"
              value={values.endsAt}
              onChange={(e) => set('endsAt', e.target.value)}
            />
          )}
        </Field>
      </div>
      {yourTime && (
        <p className="bg-waiting text-waiting-ink rounded-tag flex w-fit max-w-full items-start gap-1.5 px-2.5 py-1.5 text-sm font-semibold">
          <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>Your time: {yourTime}</span>
        </p>
      )}
    </>
  );
}
