import type { ReactNode } from 'react';

const WHEN = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** A moment in the moderator's own time zone, with the year: accounts and reports can be old. */
export function When({ iso }: { iso: string }) {
  return <time dateTime={iso}>{WHEN.format(new Date(iso))}</time>;
}

interface Props {
  label: string;
  className?: string;
  children: ReactNode;
}

/** One labelled value in a `<dl>`. Values are often user text: plain text, wrapped wherever it must. */
export function Fact({ label, className = '', children }: Props) {
  return (
    // `min-w-0`: in a grid, a long unbroken value would otherwise widen the page instead of wrapping.
    <div className={`min-w-0 ${className}`}>
      <dt className="text-ink-muted text-xs font-semibold">{label}</dt>
      <dd className="wrap-anywhere whitespace-pre-wrap">{children}</dd>
    </div>
  );
}
