import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import {
  EVENT_CAPACITY_MAX,
  EVENT_DESCRIPTION_MAX,
  EVENT_LOCATION_MAX,
  EVENT_TAG_MAX,
  EVENT_TITLE_MAX,
  type Event,
  type EventRequest,
} from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { AudiencePicker } from './AudiencePicker';
import { knownTimeZones } from './time';
import {
  emptyValues,
  validateEvent,
  valuesFromEvent,
  type EventFormValues,
  type FieldErrors,
} from './validation';

const INPUT = 'border-line bg-surface-raised rounded-control min-h-11 w-full border px-3';

function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: (props: { id: string; describedBy: string | undefined; invalid: boolean }) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      {children({ id, describedBy: describedBy || undefined, invalid: Boolean(error) })}
      {hint && (
        <p id={hintId} className="text-ink-muted text-sm">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-sm font-medium">
          {error}
        </p>
      )}
    </div>
  );
}

/** What went wrong on the server, in words that don't depend on its text. */
function SubmitError({ error }: { error: unknown }) {
  if (error instanceof ApiError && error.status === 400) {
    return (
      <p role="alert">
        The event wasn’t accepted. Check the times and the details, then try again.
      </p>
    );
  }
  if (error instanceof ApiError && error.status === 404) {
    return (
      <p role="alert">
        Part of the audience can’t be used, such as a server you’re no longer discoverable in or
        someone you can’t pick any more. Remove it and try again. Nothing was saved.
      </p>
    );
  }
  if (error instanceof ApiError && error.status === 409) {
    return <p role="alert">This event has been cancelled, so it can’t be edited.</p>;
  }
  return <ErrorState error={error} />;
}

interface Props {
  /** The event being edited; omit to create. */
  event?: Event;
  /** The profile's time zone: the default for a new event. */
  defaultTimeZone: string;
  submitLabel: string;
  pending: boolean;
  error: unknown;
  onSubmit: (request: EventRequest) => void;
}

/** Create and edit share this form. Editing sends the whole event back (D-012: a full replace). */
export function EventForm({
  event,
  defaultTimeZone,
  submitLabel,
  pending,
  error,
  onSubmit,
}: Props) {
  const [values, setValues] = useState<EventFormValues>(() =>
    event ? valuesFromEvent(event) : emptyValues(defaultTimeZone),
  );
  const [errors, setErrors] = useState<FieldErrors>({});
  const zoneListId = useId();
  const set = <K extends keyof EventFormValues>(key: K, value: EventFormValues[K]) => {
    setValues((v) => ({ ...v, [key]: value }));
    // Stop shouting about a field as soon as it's being fixed.
    setErrors((e) => ({ ...e, [key]: undefined }));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const result = validateEvent(values, new Date(), event);
    setErrors(result.errors);
    if (result.request) onSubmit(result.request);
  };

  const errorCount = Object.values(errors).filter(Boolean).length;
  const zones = knownTimeZones();

  return (
    <form onSubmit={submit} noValidate className="space-y-8">
      <section aria-labelledby="details-heading" className="space-y-4">
        <h2 id="details-heading" className="text-xl font-semibold">
          Details
        </h2>
        <Field label="Title" error={errors.title}>
          {(p) => (
            <input
              id={p.id}
              type="text"
              value={values.title}
              maxLength={EVENT_TITLE_MAX}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('title', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
        <Field label="Description (optional)" error={errors.description}>
          {(p) => (
            <textarea
              id={p.id}
              value={values.description}
              rows={4}
              maxLength={EVENT_DESCRIPTION_MAX}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('description', e.target.value)}
              className={`${INPUT} py-2`}
            />
          )}
        </Field>
        <Field label="Location" error={errors.location}>
          {(p) => (
            <input
              id={p.id}
              type="text"
              value={values.location}
              maxLength={EVENT_LOCATION_MAX}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('location', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
        <label className="flex min-h-11 items-center gap-3">
          <input
            type="checkbox"
            checked={values.locationConfirmedOnly}
            onChange={(e) => set('locationConfirmedOnly', e.target.checked)}
            className="size-5 shrink-0"
          />
          <span>Show location only to confirmed attendees</span>
        </label>
        <Field
          label="Tag (optional)"
          hint="One short label, such as Photoshoot."
          error={errors.tag}
        >
          {(p) => (
            <input
              id={p.id}
              type="text"
              value={values.tag}
              maxLength={EVENT_TAG_MAX}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('tag', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
      </section>

      <section aria-labelledby="when-heading" className="space-y-4">
        <h2 id="when-heading" className="text-xl font-semibold">
          When
        </h2>
        <Field
          label="Time zone"
          hint="The event’s own time zone, where the convention is. Times below are read in it."
          error={errors.timeZone}
        >
          {(p) => (
            <>
              <input
                id={p.id}
                type="text"
                list={zoneListId}
                value={values.timeZone}
                maxLength={64}
                autoComplete="off"
                aria-invalid={p.invalid}
                aria-describedby={p.describedBy}
                onChange={(e) => set('timeZone', e.target.value)}
                className={INPUT}
              />
              <datalist id={zoneListId}>
                {zones.map((z) => (
                  <option key={z} value={z} />
                ))}
              </datalist>
            </>
          )}
        </Field>
        <Field label="Starts" error={errors.startsAt}>
          {(p) => (
            <input
              id={p.id}
              type="datetime-local"
              value={values.startsAt}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('startsAt', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
        <Field label="Ends (optional)" hint="At most 7 days after it starts." error={errors.endsAt}>
          {(p) => (
            <input
              id={p.id}
              type="datetime-local"
              value={values.endsAt}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('endsAt', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
      </section>

      <section aria-labelledby="spots-heading" className="space-y-4">
        <h2 id="spots-heading" className="text-xl font-semibold">
          Spots (optional)
        </h2>
        <p className="text-ink-muted text-sm">
          Saved with the event now. Signing up for spots arrives in a later update.
        </p>
        <Field label="Capacity" error={errors.capacity}>
          {(p) => (
            <input
              id={p.id}
              type="text"
              inputMode="numeric"
              maxLength={String(EVENT_CAPACITY_MAX).length}
              value={values.capacity}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('capacity', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
        <Field
          label="Signup opens"
          hint="Before the start, and at most a year before it."
          error={errors.signupOpensAt}
        >
          {(p) => (
            <input
              id={p.id}
              type="datetime-local"
              value={values.signupOpensAt}
              aria-invalid={p.invalid}
              aria-describedby={p.describedBy}
              onChange={(e) => set('signupOpensAt', e.target.value)}
              className={INPUT}
            />
          )}
        </Field>
      </section>

      <div className="space-y-2">
        <AudiencePicker value={values.audience} onChange={(a) => set('audience', a)} />
        {errors.audience && (
          <p role="alert" className="text-sm font-medium">
            {errors.audience}
          </p>
        )}
      </div>

      <div className="space-y-3">
        {errorCount > 0 && (
          <p role="alert" className="font-medium">
            {errorCount === 1
              ? 'There’s 1 thing to fix.'
              : `There are ${errorCount} things to fix.`}
          </p>
        )}
        {error !== null && error !== undefined && <SubmitError error={error} />}
        <button type="submit" className="btn btn-primary w-full sm:w-auto" disabled={pending}>
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
