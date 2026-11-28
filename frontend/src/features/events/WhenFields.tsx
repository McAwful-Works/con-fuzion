import { Info } from 'lucide-react';
import type { Event } from '../../api/events';
import { Field } from './formFields';
import { StartPicker } from './StartPicker';
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

/** `event` is the one being edited; a new event has none. */
export function WhenFields({ values, errors, set, event }: SectionProps & { event?: Event }) {
  const yourTime = useYourTime(values);
  return (
    <>
      <TimeZonePicker
        value={values.timeZone}
        onChange={(zone) => set('timeZone', zone)}
        hint={`Search by city or zone.${event ? '' : ' Defaults to the zone you used last.'}`}
        error={errors.timeZone}
      />
      <StartPicker values={values} errors={errors} set={set} event={event}>
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
        {yourTime && (
          <p className="bg-waiting text-waiting-ink rounded-tag flex w-fit max-w-full items-start gap-1.5 px-2.5 py-1.5 text-sm font-semibold">
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span>Your time: {yourTime}</span>
          </p>
        )}
      </StartPicker>
    </>
  );
}
