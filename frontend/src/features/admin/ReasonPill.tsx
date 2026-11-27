import type { Report } from '../../api/admin';
import { reasonLabel } from './labels';

const COLORS: Record<Report['reason'], string> = {
  // A pale, opaque coral, mixed from the tokens so it keeps its contrast on a selected row.
  UNSAFE:
    'bg-[color-mix(in_srgb,var(--color-accent)_15%,var(--color-surface-raised))] text-accent-text',
  HARASSMENT: 'pill-waitlisted',
  INAPPROPRIATE: 'pill-waitlisted',
  SPAM: 'pill-hosting',
  OTHER: 'pill-full',
};

/** Why something was reported. The label wraps where a table column is narrow. */
export function ReasonPill({ reason }: { reason: Report['reason'] }) {
  return (
    <span className={`pill py-0.5 whitespace-normal xl:whitespace-nowrap ${COLORS[reason]}`}>
      {reasonLabel(reason)}
    </span>
  );
}
