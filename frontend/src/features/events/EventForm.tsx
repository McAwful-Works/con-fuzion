import { ChevronLeft, PartyPopper, Save } from 'lucide-react';
import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import {
  AUDIENCE_MAX,
  CONFIRM_WINDOW_MAX,
  EVENT_CAPACITY_MAX,
  type Event,
  type EventRequest,
} from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { audienceSummary } from './audience';
import { AudiencePicker } from './AudiencePicker';
import { DetailsFields } from './DetailsFields';
import { EventPreview } from './EventPreview';
import { SectionCard } from './formFields';
import { lastUsedTimeZone, rememberTimeZone } from './time';
import {
  emptyValues,
  validateEvent,
  valuesFromEvent,
  type EventFormValues,
  type FieldErrors,
  type SectionProps,
} from './validation';
import { WhenFields } from './WhenFields';

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
    // CAPACITY_BELOW_CONFIRMED is worded by ErrorState; a plain 409 means the event was cancelled.
    if (error.code === 'CAPACITY_BELOW_CONFIRMED') return <ErrorState error={error} />;
    return <p role="alert">This event has been cancelled, so it can’t be edited.</p>;
  }
  return <ErrorState error={error} />;
}

function Heading({ event }: { event?: Event }) {
  if (!event) {
    return (
      <div>
        {/* Phones have this in the top bar. */}
        <p className="text-ink-muted text-xs font-extrabold tracking-wider uppercase max-lg:hidden">
          Host a meetup
        </p>
        <h1 className="text-3xl font-extrabold">New meetup</h1>
      </div>
    );
  }
  return (
    <div>
      {/* Phones have Back in the top bar. */}
      <Link
        to={`/events/${event.id}`}
        className="hidden min-h-11 w-fit items-center gap-1 text-sm font-semibold lg:flex"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        Back to the event
      </Link>
      <h1 className="text-3xl font-extrabold">Edit meetup</h1>
    </div>
  );
}

interface Props {
  /** The event being edited; omit to create. */
  event?: Event;
  /** The profile's zone, or the browser's: a new event's default when no zone was used last. */
  defaultTimeZone: string;
  submitLabel: string;
  pending: boolean;
  error: unknown;
  onSubmit: (request: EventRequest) => void;
}

/**
 * Create and edit share this form: its sections, and beside them on desktop (below them on phones)
 * the preview, who it's visible to and the button. Editing sends the whole event back (D-012: a
 * full replace).
 */
export function EventForm({
  event,
  defaultTimeZone,
  submitLabel,
  pending,
  error,
  onSubmit,
}: Props) {
  // A new event starts in the zone the last one used, else the default the page worked out.
  const [values, setValues] = useState<EventFormValues>(() =>
    event ? valuesFromEvent(event) : emptyValues(lastUsedTimeZone() ?? defaultTimeZone),
  );
  const [errors, setErrors] = useState<FieldErrors>({});
  const set: SectionProps['set'] = (key, value) => {
    setValues((v) => ({ ...v, [key]: value }));
    // Stop shouting about a field as soon as it's being fixed.
    setErrors((e) => ({ ...e, [key]: undefined }));
  };
  const section = { values, errors, set };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const result = validateEvent(values, new Date(), event);
    setErrors(result.errors);
    if (!result.request) return;
    if (!event) rememberTimeZone(result.request.timeZone);
    onSubmit(result.request);
  };

  const errorCount = Object.values(errors).filter(Boolean).length;
  const SubmitIcon = event ? Save : PartyPopper;

  return (
    // `data-event-page` gives the shell's main column room for the side column on desktop.
    <form
      data-event-page
      onSubmit={submit}
      noValidate
      className="w-full space-y-5 lg:grid lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start lg:gap-8 lg:space-y-0"
    >
      <div className="min-w-0 space-y-5">
        <Heading event={event} />
        <SectionCard id="details" title="Details" sub="What it is and where to meet.">
          <DetailsFields {...section} />
        </SectionCard>
        <SectionCard
          id="when"
          title="When"
          sub="Times are in the event’s own time zone, where the con is."
        >
          <WhenFields {...section} isNew={!event} />
        </SectionCard>

        <section aria-labelledby="spots-heading" className="space-y-4">
          <h2 id="spots-heading" className="text-xl font-semibold">
            Spots (optional)
          </h2>
          <Field
            label="Capacity"
            hint="The total headcount, and it includes you: you hold the first spot, so the smallest is 2. Leave it empty for no limit. When it’s full, people join a waitlist."
            error={errors.capacity}
          >
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
          <Field
            label="Offer window (minutes)"
            hint="When a spot frees up, the next person in line gets this long to accept it, from 10 minutes to 24 hours (1440). If the event starts sooner than that, they’re confirmed straight away."
            error={errors.confirmWindowMinutes}
          >
            {(p) => (
              <input
                id={p.id}
                type="text"
                inputMode="numeric"
                maxLength={String(CONFIRM_WINDOW_MAX).length}
                value={values.confirmWindowMinutes}
                aria-invalid={p.invalid}
                aria-describedby={p.describedBy}
                onChange={(e) => set('confirmWindowMinutes', e.target.value)}
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
      </div>

      {/* The padding and matching margin leave room for the shadows and focus rings when it scrolls. */}
      <div className="space-y-4 lg:sticky lg:top-6 lg:-m-2 lg:max-h-[calc(100dvh-3rem)] lg:overflow-y-auto lg:p-2">
        <EventPreview values={values} original={event} />
        <section aria-labelledby="visible-heading" className="card space-y-1 p-4">
          <h2 id="visible-heading" className="text-sm font-extrabold">
            Visible to
          </h2>
          <p aria-live="polite" className="text-sm break-words">
            {audienceSummary(values.audience)}{' '}
            <span className="text-ink-muted">
              ({values.audience.length} of {AUDIENCE_MAX} entries)
            </span>
          </p>
        </section>
        {errorCount > 0 && (
          <p role="alert" className="text-danger font-semibold">
            {errorCount === 1
              ? 'There’s 1 thing to fix.'
              : `There are ${errorCount} things to fix.`}
          </p>
        )}
        {error !== null && error !== undefined && <SubmitError error={error} />}
        <button type="submit" className="btn btn-primary w-full" disabled={pending}>
          <SubmitIcon aria-hidden="true" className="size-4" />
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
