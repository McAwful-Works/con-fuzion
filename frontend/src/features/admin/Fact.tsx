import type { ReactNode } from 'react';

const WHEN = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** A moment in the moderator's own time zone, with the year: accounts and reports can be old. */
export function When({ iso }: { iso: string }) {
  return <time dateTime={iso}>{WHEN.format(new Date(iso))}</time>;
}

/** One labelled value in a `<dl>`. Values are often user text: plain text, wrapped wherever it must. */
export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-ink-muted text-sm">{label}</dt>
      <dd className="break-words whitespace-pre-wrap">{children}</dd>
    </div>
  );
}
