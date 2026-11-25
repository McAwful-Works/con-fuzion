import { useMutation } from '@tanstack/react-query';
import { api, type ContractPath } from './client';
import type { components } from './schema';
import { blockUser } from './social';

type Schemas = components['schemas'];
export type ReportRequest = Schemas['ReportRequest'];
export type ReportReason = ReportRequest['reason'];

const REPORTS = '/reports' satisfies ContractPath;

/** D-015 rule 13: a report note is up to 1,000 characters. */
export const REPORT_NOTE_MAX = 1000;

export interface SendReport {
  targetType: ReportRequest['targetType'];
  targetId: string;
  reason: ReportReason;
  note: string;
  /** Block this person once the report is in (the report sheet's optional second step). */
  alsoBlock?: string;
}

/** What happened after the report was accepted: whether the optional block went through. */
export interface ReportResult {
  blockFailed: boolean;
}

/**
 * `POST /api/reports`. D-015 rule 6: it answers 204 for a new report and for a repeat, so the caller
 * shows one message either way. The block comes after, and a failed block doesn't undo the report.
 */
export function useSendReport() {
  return useMutation({
    mutationFn: async ({ alsoBlock, note, ...rest }: SendReport): Promise<ReportResult> => {
      const trimmed = note.trim();
      await api<void>(REPORTS, {
        method: 'POST',
        body: { ...rest, note: trimmed || null } satisfies ReportRequest,
      });
      if (!alsoBlock) return { blockFailed: false };
      try {
        await blockUser(alsoBlock);
        return { blockFailed: false };
      } catch {
        return { blockFailed: true };
      }
    },
  });
}
