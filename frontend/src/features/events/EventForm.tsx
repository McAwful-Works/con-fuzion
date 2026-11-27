import { ChevronLeft, PartyPopper, Save } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { ApiError } from '../../api/client';
import { AUDIENCE_MAX, type Event, type EventRequest } from '../../api/events';
import { ErrorState } from '../../components/ErrorState';
import { useDesktop } from '../../components/shell/useDesktop';
import { audienceSummary } from './audience';
import { AudiencePicker } from './AudiencePicker';
import { DetailsFields } from './DetailsFields';
import { EventPreview } from './EventPreview';
import { FieldError, SectionCard } from './formFields';
import {
  firstStepWithErrors,
  REVIEW_STEP,
  StepBar,
  StepHeader,
  stepErrors,
  useSteps,
} from './PhoneSteps';
import { SpotsFields } from './SpotsFields';
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

/** The desktop page's heading. A phone shows its step's instead. */
function Heading({ event }: { event?: Event }) {
  if (!event) {
    return (
      <div>
        <p className="text-ink-muted text-xs font-extrabold tracking-wider uppercase">
          Host a meetup
        </p>
        <h1 className="text-3xl font-extrabold">New meetup</h1>
      </div>
    );
  }
  return (
    <div>
      <Link
        to={`/events/${event.id}`}
        className="flex min-h-11 w-fit items-center gap-1 text-sm font-semibold"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        Back to the event
      </Link>
      <h1 className="text-3xl font-extrabold">Edit meetup</h1>
    </div>
  );
}

/** On desktop, `/events/:id/edit#audience` scrolls the page to "Who can see this". */
function useOpenAtHash(desktop: boolean, atAudience: boolean) {
  // Once, on arrival: widening the window later mustn't take focus from what's being typed.
  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    arrived.current = true;
    if (!desktop || !atAudience) return;
    const heading = document.getElementById('audience-heading');
    // The heading takes focus, so a keyboard or screen reader starts there too.
    heading?.focus({ preventScroll: true });
    // Optional call: jsdom has no scrollIntoView.
    heading?.closest('section')?.scrollIntoView?.();
  }, [desktop, atAudience]);
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
 * Create and edit share this form. Desktop is one page: four sections, and beside them the preview,
 * who it's visible to and the button. Phones show a section at a time, then Review. The sections
 * stay mounted, so nothing typed is lost between steps. Editing sends the whole event back (D-012:
 * a full replace).
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
  const desktop = useDesktop();
  const atAudience = useLocation().hash === '#audience';
  const { step, go, heading } = useSteps(desktop, atAudience);
  useOpenAtHash(desktop, atAudience);
  const review = step === REVIEW_STEP;
  /** What a section is on this screen: a card among the others, or the one step shown. */
  const shown = (index: number) => ({ plain: !desktop, hidden: !desktop && step !== index });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const result = validateEvent(values, new Date(), event);
    // A phone's Next, and Enter before Review: only this step's fields hold it back.
    if (!desktop && !review) {
      const here = stepErrors(result.errors, step);
      setErrors(here);
      if (Object.keys(here).length === 0) go(step + 1);
      return;
    }
    setErrors(result.errors);
    if (!result.request) {
      // Something that passed earlier can fail by now, such as a start that has since gone by.
      if (!desktop) go(firstStepWithErrors(result.errors));
      return;
    }
    if (!event) rememberTimeZone(result.request.timeZone);
    onSubmit(result.request);
  };

  const visibleErrors = desktop || review ? errors : stepErrors(errors, step);
  const errorCount = Object.values(visibleErrors).filter(Boolean).length;
  const SubmitIcon = event ? Save : PartyPopper;
  const submitContent = (
    <>
      <SubmitIcon aria-hidden="true" className="size-4" />
      {submitLabel}
    </>
  );
  // Beside the desktop button, or in the phone's bar. The server's answer belongs to Review.
  const messages = (
    <>
      {errorCount > 0 && (
        <p role="alert" className="text-danger font-semibold">
          {errorCount === 1 ? 'There’s 1 thing to fix.' : `There are ${errorCount} things to fix.`}
        </p>
      )}
      {(desktop || review) && error !== null && error !== undefined && (
        <SubmitError error={error} />
      )}
    </>
  );

  return (
    // `data-event-page` gives the shell's main column room for the side column on desktop.
    <form
      data-event-page
      onSubmit={submit}
      noValidate
      className="flex w-full flex-1 flex-col gap-5 lg:grid lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start lg:gap-8"
    >
      <div className="min-w-0 space-y-5">
        {desktop ? <Heading event={event} /> : <StepHeader step={step} ref={heading} />}
        <SectionCard id="details" title="Details" sub="What it is and where to meet." {...shown(0)}>
          <DetailsFields {...section} />
        </SectionCard>
        <SectionCard
          id="when"
          title="When"
          sub="Times are in the event’s own time zone, where the con is."
          {...shown(1)}
        >
          <WhenFields {...section} isNew={!event} />
        </SectionCard>
        <SectionCard id="spots" title="Spots" sub="First come, first served." {...shown(2)}>
          <SpotsFields {...section} originalWindow={event?.confirmWindowMinutes} />
        </SectionCard>
        <SectionCard
          id="audience"
          title="Who can see this"
          sub="Only the people you pick can find it. Pick at least one."
          {...shown(3)}
        >
          <AudiencePicker value={values.audience} onChange={(a) => set('audience', a)} />
          <FieldError>{errors.audience}</FieldError>
        </SectionCard>
      </div>

      {/* The padding and matching margin leave room for the shadows and focus rings when it scrolls. */}
      <div
        hidden={!desktop && !review}
        className="space-y-4 lg:sticky lg:top-6 lg:-m-2 lg:max-h-[calc(100dvh-3rem)] lg:overflow-y-auto lg:p-2"
      >
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
        {desktop && (
          <>
            {messages}
            <button type="submit" className="btn btn-primary w-full" disabled={pending}>
              {submitContent}
            </button>
          </>
        )}
      </div>

      {!desktop && (
        <StepBar
          step={step}
          pending={pending}
          submitContent={submitContent}
          onBack={() => {
            setErrors({});
            go(step - 1);
          }}
        >
          {messages}
        </StepBar>
      )}
    </form>
  );
}
