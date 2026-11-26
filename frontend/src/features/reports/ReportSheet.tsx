import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { REPORT_NOTE_MAX, useSendReport, type ReportReason } from '../../api/reports';
import { useInvalidateSocial } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';

export const REASONS: { value: ReportReason; label: string }[] = [
  { value: 'HARASSMENT', label: 'Harassment or bullying' },
  { value: 'UNSAFE', label: 'Unsafe or dangerous' },
  { value: 'SPAM', label: 'Spam or a scam' },
  { value: 'INAPPROPRIATE', label: 'Inappropriate' },
  { value: 'OTHER', label: 'Something else' },
];

interface Props {
  targetType: 'EVENT' | 'USER';
  targetId: string;
  /** What is being reported, for the heading: an event's title or a person's name. Plain text. */
  label: string;
  /** Offers "also block" for this person: the reported user, or an event's host. */
  blockable?: { id: string; displayName: string };
  onDone: () => void;
}

/**
 * An inline form to report an event or a person: a reason, an optional note, and optionally a block.
 * It answers the same way whether the report is new or a repeat (D-015 rule 6), and never says
 * anything about what the moderators do with it.
 */
export function ReportSheet({ targetType, targetId, label, blockable, onDone }: Props) {
  const send = useSendReport();
  const invalidateSocial = useInvalidateSocial();
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [note, setNote] = useState('');
  const [alsoBlock, setAlsoBlock] = useState(false);
  const ids = { title: useId(), note: useId(), count: useId() };
  const titleRef = useRef<HTMLHeadingElement>(null);
  const blocked = send.isSuccess && alsoBlock && !send.data.blockFailed;

  useEffect(() => {
    // Move focus into the sheet, and give it back to whatever opened it when it closes.
    const opener = document.activeElement;
    titleRef.current?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);

  // The form is replaced by the thanks, which unmounts the button that had focus: move focus to the heading.
  useEffect(() => {
    if (send.isSuccess) titleRef.current?.focus();
  }, [send.isSuccess]);

  const close = () => {
    // The block changes who you can see, so lists refresh now and not while the thanks was showing.
    if (blocked) void invalidateSocial();
    onDone();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!reason || send.isPending) return;
    send.mutate({
      targetType,
      targetId,
      reason,
      note,
      alsoBlock: alsoBlock && blockable ? blockable.id : undefined,
    });
  };

  const heading = (
    <h3 id={ids.title} ref={titleRef} tabIndex={-1} className="font-semibold">
      Report {label}
    </h3>
  );

  if (send.isSuccess) {
    return (
      <div
        role="group"
        aria-labelledby={ids.title}
        className="border-line bg-surface-raised rounded-card space-y-3 border p-4"
      >
        {heading}
        <p role="status" className="text-sm">
          Thanks. The moderators will look at it.
          {blocked && blockable && ` You’ve also blocked ${blockable.displayName}.`}
        </p>
        {alsoBlock && send.data.blockFailed && blockable && (
          <p role="alert" className="text-sm">
            Your report was sent, but we couldn’t block {blockable.displayName}.
          </p>
        )}
        <button type="button" className="btn btn-primary" onClick={close}>
          Close
        </button>
      </div>
    );
  }

  return (
    <form
      aria-labelledby={ids.title}
      onSubmit={submit}
      onKeyDown={(e) => e.key === 'Escape' && !send.isPending && onDone()}
      className="border-line bg-surface-raised rounded-card space-y-4 border p-4"
    >
      {heading}
      <p className="text-sm">
        Moderators read every report. The person you report isn’t told it was you.
      </p>
      <fieldset className="space-y-1">
        <legend className="mb-1 text-sm font-medium">What’s the problem?</legend>
        {REASONS.map((r) => (
          <label key={r.value} className="flex min-h-11 items-center gap-3">
            <input
              type="radio"
              name={`${ids.title}-reason`}
              value={r.value}
              checked={reason === r.value}
              onChange={() => setReason(r.value)}
              className="size-5 shrink-0"
            />
            <span>{r.label}</span>
          </label>
        ))}
      </fieldset>
      <div className="space-y-1">
        <label htmlFor={ids.note} className="block text-sm font-medium">
          Anything else we should know? (optional)
        </label>
        <textarea
          id={ids.note}
          value={note}
          rows={4}
          maxLength={REPORT_NOTE_MAX}
          aria-describedby={ids.count}
          onChange={(e) => setNote(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3 py-2"
        />
        <p id={ids.count} className="text-ink-muted text-sm">
          {note.length} of {REPORT_NOTE_MAX} characters. Plain text only.
        </p>
      </div>
      {blockable && (
        <label className="flex min-h-11 items-start gap-3">
          <input
            type="checkbox"
            checked={alsoBlock}
            onChange={(e) => setAlsoBlock(e.target.checked)}
            className="mt-2.5 size-5 shrink-0"
          />
          <span className="py-2">
            Also block {blockable.displayName}. You won’t see each other’s events, and they aren’t
            told.
          </span>
        </label>
      )}
      {send.isError && <ErrorState error={send.error} />}
      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          className="btn btn-secondary"
          disabled={send.isPending}
          onClick={onDone}
        >
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={!reason || send.isPending}>
          {send.isPending ? 'Sending…' : 'Send report'}
        </button>
      </div>
    </form>
  );
}
