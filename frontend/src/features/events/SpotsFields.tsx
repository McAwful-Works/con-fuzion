import { Minus, Plus } from 'lucide-react';
import { useId, useState } from 'react';
import { EVENT_CAPACITY_MAX, EVENT_CAPACITY_MIN } from '../../api/events';
import { Field, FieldError, SwitchRow } from './formFields';
import type { SectionProps } from './validation';

/** Where the stepper starts when the host first limits the spots. */
const DEFAULT_CAPACITY = '8';
/** Offer windows to choose from, in minutes: all within D-014's 10 minutes to 24 hours. */
const OFFER_WINDOWS = [10, 15, 30, 60, 120, 240, 720, 1440];

function duration(minutes: number): string {
  if (minutes < 60 || minutes % 60 !== 0) return `${minutes} minutes`;
  return minutes === 60 ? '1 hour' : `${minutes / 60} hours`;
}

/** One step from what's typed, kept within the limits. Anything that isn't a number starts at the smallest. */
function step(text: string, by: number): string {
  const typed = /^\d+$/.test(text.trim()) ? Number(text) : null;
  const next = typed === null ? EVENT_CAPACITY_MIN : typed + by;
  return String(Math.min(EVENT_CAPACITY_MAX, Math.max(EVENT_CAPACITY_MIN, next)));
}

// `aria-disabled`, not `disabled`: a keyboard user who steps to a limit keeps their place.
const STEP_BUTTON =
  'flex size-12 shrink-0 items-center justify-center -outline-offset-4 aria-disabled:opacity-40';

/** The total headcount: a number field between a minus and a plus. */
function Stepper({
  value,
  error,
  onChange,
}: {
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const number = Number(value);
  return (
    <div className="field">
      <label htmlFor={id} className="field-label">
        Capacity
      </label>
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2">
        <div
          className={`rounded-control bg-surface-raised flex w-fit overflow-hidden border-2 ${error ? 'border-danger' : 'border-control-outline'}`}
        >
          <button
            type="button"
            aria-label="Fewer spots"
            aria-disabled={value !== '' && number <= EVENT_CAPACITY_MIN}
            onClick={() => onChange(step(value, -1))}
            className={STEP_BUTTON}
          >
            <Minus aria-hidden="true" className="size-5" />
          </button>
          <input
            id={id}
            type="text"
            inputMode="numeric"
            aria-invalid={Boolean(error)}
            aria-describedby={error ? `${id}-hint ${id}-error` : `${id}-hint`}
            maxLength={String(EVENT_CAPACITY_MAX).length}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            className="font-head border-control-outline h-12 w-20 border-x-2 bg-transparent text-center text-lg font-extrabold -outline-offset-4"
          />
          <button
            type="button"
            aria-label="More spots"
            aria-disabled={number >= EVENT_CAPACITY_MAX}
            onClick={() => onChange(step(value, 1))}
            className={STEP_BUTTON}
          >
            <Plus aria-hidden="true" className="size-5" />
          </button>
        </div>
        <p id={`${id}-hint`} className="hint min-w-48 flex-1">
          You hold the first spot, so the smallest is {EVENT_CAPACITY_MIN}. When it’s full, people
          join a waitlist.
        </p>
      </div>
      <FieldError id={`${id}-error`}>{error}</FieldError>
    </div>
  );
}

export function SpotsFields({
  values,
  errors,
  set,
  originalWindow,
}: SectionProps & { originalWindow?: number }) {
  // What the stepper held when the limit was switched off, so switching it back on returns to it.
  const [kept, setKept] = useState('');
  const current = Number(values.confirmWindowMinutes);
  // An event's own window stays a choice even when it isn't one of the presets.
  const windows = [...new Set([...OFFER_WINDOWS, current, originalWindow ?? current])].sort(
    (a, b) => a - b,
  );

  return (
    <>
      <SwitchRow
        label="Limit the spots"
        hint="Includes you. Off means no limit."
        checked={values.capacity !== null}
        onChange={(on) => {
          if (!on) setKept(values.capacity ?? '');
          set('capacity', on ? kept || DEFAULT_CAPACITY : null);
        }}
      />
      {values.capacity !== null && (
        <Stepper
          value={values.capacity}
          error={errors.capacity}
          onChange={(capacity) => set('capacity', capacity)}
        />
      )}
      <div className="grid items-start gap-4 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
        <Field
          label="Signup opens (optional)"
          hint="Empty means right away. Before the start, and at most a year before it."
          error={errors.signupOpensAt}
        >
          {(p) => (
            <input
              {...p}
              type="datetime-local"
              value={values.signupOpensAt}
              onChange={(e) => set('signupOpensAt', e.target.value)}
            />
          )}
        </Field>
        <Field
          label="Time to accept an offer"
          hint="How long the next person in line has to say yes to a freed spot. If the event starts sooner than that, they’re confirmed straight away."
          error={errors.confirmWindowMinutes}
        >
          {(p) => (
            <select
              {...p}
              value={values.confirmWindowMinutes}
              onChange={(e) => set('confirmWindowMinutes', e.target.value)}
            >
              {windows.map((minutes) => (
                <option key={minutes} value={minutes}>
                  {duration(minutes)}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
    </>
  );
}
