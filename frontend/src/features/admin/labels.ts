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

/** "14 min", "2 hr", "3 days": a length of time, rounded down. */
export function duration(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  // Also what a clock a little behind the server's gets for a report just sent.
  if (minutes < 1) return '<1 min';
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr`;
  const days = Math.floor(hours / 24);
  return days === 1 ? '1 day' : `${days} days`;
}

/** "Event: Photo walk". The label is null once the event is purged or the account deleted. */
export function targetName(report: Report): string {
  const kind = report.targetType === 'EVENT' ? 'Event' : 'Person';
  return `${kind}: ${report.targetLabel ?? 'no longer exists'}`;
}
