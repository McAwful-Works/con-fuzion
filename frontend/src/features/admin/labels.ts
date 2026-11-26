import type { Report, ReportStatus } from '../../api/admin';
import { REASONS } from '../reports/ReportSheet';

export const STATUS_LABELS: Record<ReportStatus, string> = {
  OPEN: 'Open',
  ACTIONED: 'Actioned',
  DISMISSED: 'Dismissed',
};

export function reasonLabel(reason: Report['reason']): string {
  return REASONS.find((r) => r.value === reason)?.label ?? reason;
}

/** "Event: Photo walk". The label is null once the event is purged or the account deleted. */
export function targetName(report: Report): string {
  const kind = report.targetType === 'EVENT' ? 'Event' : 'Person';
  return `${kind}: ${report.targetLabel ?? 'no longer exists'}`;
}
