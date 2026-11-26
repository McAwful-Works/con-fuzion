import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ErrorState } from '../../components/ErrorState';

interface Note {
  label: string;
  max: number;
  required?: boolean;
}

interface FormProps {
  title: string;
  confirm: string;
  /** What will happen, then any extra fields. */
  children: ReactNode;
  note?: Note;
  /** False while the extra fields aren't filled in. */
  ready?: boolean;
  pending: boolean;
  error: unknown;
  /** Gets the trimmed note, which is empty when there's none. */
  onSubmit: (note: string) => void;
  onClose: () => void;
}

function ActionForm({ title, confirm, children, note, ready = true, ...props }: FormProps) {
  const { pending, error, onSubmit, onClose } = props;
  const [text, setText] = useState('');
  const ids = { title: useId(), note: useId(), count: useId() };
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => titleRef.current?.focus(), []);

  const valid = ready && (!note?.required || text.trim() !== '');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (valid && !pending) onSubmit(text.trim());
  };

  return (
    <form
      aria-labelledby={ids.title}
      onSubmit={submit}
      onKeyDown={(e) => e.key === 'Escape' && !pending && onClose()}
      className="border-line bg-surface-raised rounded-card w-full space-y-3 border p-4"
    >
      <h3 id={ids.title} ref={titleRef} tabIndex={-1} className="font-semibold break-words">
        {title}
      </h3>
      {children}
      {note && (
        <div className="space-y-1">
          <label htmlFor={ids.note} className="block text-sm font-medium">
            {note.label}
          </label>
          <textarea
            id={ids.note}
            value={text}
            rows={3}
            maxLength={note.max}
            required={note.required}
            aria-describedby={ids.count}
            onChange={(e) => setText(e.target.value)}
            className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3 py-2"
          />
          <p id={ids.count} className="text-ink-muted text-sm">
            {text.length} of {note.max} characters. Plain text only.
          </p>
        </div>
      )}
      {error !== null && <ErrorState error={error} />}
      <div className="flex flex-col gap-2 sm:flex-row">
        <button type="button" className="btn btn-secondary" disabled={pending} onClick={onClose}>
          Never mind
        </button>
        <button type="submit" className="btn btn-primary" disabled={!valid || pending}>
          {pending ? 'Working…' : confirm}
        </button>
      </div>
    </form>
  );
}

interface Props extends Pick<FormProps, 'confirm' | 'children' | 'note' | 'ready'> {
  /** The opening button, and the form's heading. */
  label: string;
  /** Clears any extra fields, so a choice made last time isn't sent by accident. */
  onOpen?: () => void;
  mutation: { isPending: boolean; error: unknown; reset: () => void };
  /** Run the mutation, and call `done` when it succeeds. */
  onSubmit: (note: string, done: () => void) => void;
}

/** A moderator action: a button that opens a form saying what will happen, with the note it needs. */
export function ModAction({ label, mutation, onOpen, onSubmit, ...form }: Props) {
  const [open, setOpen] = useState(false);
  const openerRef = useRef<HTMLButtonElement>(null);
  // The button stays mounted, so focus has somewhere to return to. It's named here because Safari
  // doesn't focus a button that's clicked, so `document.activeElement` wouldn't find it.
  const close = () => {
    openerRef.current?.focus();
    setOpen(false);
  };
  return (
    <div className="space-y-3">
      <button
        type="button"
        ref={openerRef}
        className="btn btn-secondary"
        aria-expanded={open}
        onClick={() => {
          onOpen?.();
          mutation.reset();
          setOpen(true);
        }}
      >
        {label}
      </button>
      {open && (
        <ActionForm
          {...form}
          title={label}
          pending={mutation.isPending}
          error={mutation.error}
          onClose={close}
          onSubmit={(note) => onSubmit(note, close)}
        />
      )}
    </div>
  );
}
