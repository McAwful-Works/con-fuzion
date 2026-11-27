import { ChevronRight } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import type { FieldErrors } from './validation';

/** The phone's five steps, in order. The first four are the form's sections, each with its fields. */
const STEPS: { label: string; question: string; fields: (keyof FieldErrors)[] }[] = [
  {
    label: 'Details',
    question: 'What’s the plan?',
    fields: ['title', 'tag', 'description', 'location'],
  },
  { label: 'When', question: 'When is it?', fields: ['timeZone', 'startsAt', 'endsAt'] },
  {
    label: 'Spots',
    question: 'How many spots?',
    fields: ['capacity', 'signupOpensAt', 'confirmWindowMinutes'],
  },
  { label: 'Who can see this', question: 'Who can see it?', fields: ['audience'] },
  { label: 'Review', question: 'Look good?', fields: [] },
];
const AUDIENCE_STEP = 3;
export const REVIEW_STEP = STEPS.length - 1;

/** The errors that belong to one step. */
export function stepErrors(errors: FieldErrors, step: number): FieldErrors {
  return Object.fromEntries(
    STEPS[step]!.fields.filter((field) => errors[field]).map((field) => [field, errors[field]]),
  );
}

/** The first step with something to fix, or the first step if no step claims the error. */
export function firstStepWithErrors(errors: FieldErrors): number {
  return Math.max(
    0,
    STEPS.findIndex((step) => step.fields.some((field) => errors[field])),
  );
}

/** Which step a phone shows. A move takes focus, and the view, to the new step's question. */
export function useSteps(desktop: boolean, atAudience: boolean) {
  const [step, setStep] = useState(atAudience ? AUDIENCE_STEP : 0);
  const heading = useRef<HTMLHeadingElement>(null);
  // Not on arrival, unless a link asked for a step.
  const moved = useRef(atAudience);
  useEffect(() => {
    // Once per move: a change of width mustn't take focus from what's being typed.
    const justMoved = moved.current;
    moved.current = false;
    if (desktop || !justMoved) return;
    heading.current?.focus({ preventScroll: true });
    // Optional call: jsdom has no scrollIntoView.
    heading.current?.closest('form')?.scrollIntoView?.();
  }, [desktop, step]);
  const go = (next: number) => {
    moved.current = true;
    setStep(next);
  };
  return { step, go, heading };
}

export function StepHeader({ step, ref }: { step: number; ref: Ref<HTMLHeadingElement> }) {
  const { label, question } = STEPS[step]!;
  return (
    <div className="space-y-1">
      <div
        role="progressbar"
        aria-label="Progress"
        aria-valuemin={1}
        aria-valuemax={STEPS.length}
        aria-valuenow={step + 1}
        aria-valuetext={`Step ${step + 1} of ${STEPS.length}: ${label}`}
        className="mb-4 flex gap-1.5"
      >
        {STEPS.map((s, index) => (
          <span
            key={s.label}
            className={`border-control-outline h-2 flex-1 rounded-full border-2 ${index <= step ? 'bg-accent' : 'bg-surface-sunken'}`}
          />
        ))}
      </div>
      <p className="text-ink-muted text-xs font-extrabold tracking-wider uppercase">
        Step {step + 1} of {STEPS.length} · {label}
      </p>
      <h1 ref={ref} tabIndex={-1} className="text-3xl font-extrabold">
        {question}
      </h1>
    </div>
  );
}

/**
 * The bar at the bottom of a phone screen: what went wrong, then Back and Next. Its main button
 * submits the form, which moves on or, on Review, saves.
 */
export function StepBar({
  step,
  onBack,
  pending,
  submitContent,
  children,
}: {
  step: number;
  onBack: () => void;
  pending: boolean;
  /** What the button says on Review. */
  submitContent: ReactNode;
  children: ReactNode;
}) {
  const next = STEPS[step + 1];
  return (
    <div
      data-actionbar
      className="border-outline bg-bar sticky bottom-0 z-10 -mx-4 mt-auto -mb-6 max-h-[70dvh] space-y-2 overflow-y-auto border-t-2 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur"
    >
      {children}
      <div className="flex gap-2.5">
        {step > 0 && (
          // Named for where it goes: the top bar has a Back too, which leaves the form.
          <button
            type="button"
            className="btn btn-secondary"
            aria-label={`Back to ${STEPS[step - 1]!.label}`}
            // Changes made while a save is on its way would be lost.
            disabled={pending}
            onClick={onBack}
          >
            Back
          </button>
        )}
        <button
          type="submit"
          className="btn btn-primary flex-1"
          disabled={pending}
          // A double tap on the last Next mustn't also press the button that takes its place.
          onClick={(e) => !next && e.detail > 1 && e.preventDefault()}
        >
          {next ? (
            <>
              Next: {next.label}
              <ChevronRight aria-hidden="true" className="size-4 shrink-0" />
            </>
          ) : (
            submitContent
          )}
        </button>
      </div>
    </div>
  );
}
