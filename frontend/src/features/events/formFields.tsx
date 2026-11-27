import { useId, type ReactNode } from 'react';

/** One of the form's section cards. Its heading can be focused, for links straight to a section. */
export function SectionCard({
  id,
  title,
  sub,
  children,
}: {
  id: string;
  title: string;
  sub: string;
  children: ReactNode;
}) {
  return (
    <section id={id} aria-labelledby={`${id}-heading`} className="card space-y-4 p-4 lg:p-5">
      <div>
        <h2 id={`${id}-heading`} tabIndex={-1} className="text-xl font-extrabold">
          {title}
        </h2>
        <p className="text-ink-muted text-sm">{sub}</p>
      </div>
      {children}
    </section>
  );
}

export function FieldError({ id, children }: { id?: string; children?: string }) {
  if (!children) return null;
  return (
    <p id={id} role="alert" className="text-danger text-sm font-semibold">
      {children}
    </p>
  );
}

export const CONTROL = 'field-control aria-[invalid=true]:border-danger min-w-0';

/** A labelled control. Spread the props it passes down onto the input. */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: (props: {
    id: string;
    'aria-describedby': string | undefined;
    'aria-invalid': boolean;
    className: string;
  }) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');
  return (
    <div className="field min-w-0">
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      {children({
        id,
        'aria-describedby': describedBy || undefined,
        'aria-invalid': Boolean(error),
        className: CONTROL,
      })}
      {hint && (
        <p id={hintId} className="hint">
          {hint}
        </p>
      )}
      <FieldError id={errorId}>{error}</FieldError>
    </div>
  );
}

/** A switch with its name and a line about it. Tapping the name toggles it too. */
export function SwitchRow({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <label htmlFor={id} className="block text-sm font-bold">
          {label}
        </label>
        <p id={`${id}-hint`} className="hint">
          {hint}
        </p>
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-describedby={`${id}-hint`}
        onClick={() => onChange(!checked)}
        className="switch"
      />
    </div>
  );
}
