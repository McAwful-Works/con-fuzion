import type { Report, ReportStatus } from '../../api/admin';
import { REASONS } from '../reports/ReportSheet';

export const STATUS_LABELS: Record<ReportStatus, string> = {
  OPEN: 'Open',
  ACTIONED: 'Actioned',
  DISMISSED: 'Dismissed',
};

/** A contract code in words: `DISCORD_ACCOUNT` is "Discord account". */
export function words(code: string): string {
  const text = code.toLowerCase().replaceAll('_', ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function reasonLabel(reason: Report['reason']): string {
  return REASONS.find((r) => r.value === reason)?.label ?? reason;
}

/** "Event: Photo walk". The label is null once the event is purged or the account deleted. */
export function targetName(report: Report): string {
  const kind = report.targetType === 'EVENT' ? 'Event' : 'Person';
  return `${kind}: ${report.targetLabel ?? 'no longer exists'}`;
}
