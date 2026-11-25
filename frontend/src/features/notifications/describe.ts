import type { Notification } from '../../api/notifications';
import { formatDeadline } from '../rsvp/signup';

type Change = NonNullable<Notification['payload']['changes']>[number];

const CHANGE_WORDS: Record<Change, string> = {
  START: 'the start time',
  END: 'the end time',
  LOCATION: 'the location',
};

/** "the start time", "the start time and the location", "the start time, the end time and the location". */
function listChanges(changes: Change[]): string {
  const words = changes.map((c) => CHANGE_WORDS[c]);
  if (words.length <= 1) return words[0] ?? 'the details';
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;
}

/**
 * What a notification says, from its payload alone (D-014 rule 8): the event title as it was when the
 * notification was made, plus what happened. No location or name is ever in a payload, and the title
 * is user text, so it only ever goes into plain text.
 */
export function describeNotification(n: Notification, nowMs: number): string {
  const { eventTitle, offerExpiresAt, startsAt, changes, reason } = n.payload;
  const title = eventTitle ? `“${eventTitle}”` : 'An event';
  switch (n.type) {
    case 'SPOT_OFFERED': {
      if (!offerExpiresAt) return `You were offered a spot in ${title}.`;
      return Date.parse(offerExpiresAt) > nowMs
        ? `You were offered a spot in ${title}. Accept before ${formatDeadline(offerExpiresAt)}.`
        : `You were offered a spot in ${title}. The deadline was ${formatDeadline(offerExpiresAt)}.`;
    }
    case 'SPOT_CONFIRMED':
      return `You’re confirmed for ${title}.`;
    case 'EVENT_CHANGED':
      return `${title} changed: ${listChanges(changes ?? [])}.${
        startsAt ? ` It now starts ${formatDeadline(startsAt)}.` : ''
      }`;
    case 'EVENT_CANCELLED':
      // A reason comes only with the host's copy of a moderator's cancel (D-015 rule 11).
      return reason
        ? `${title} was cancelled by the moderators. Reason: ${reason}`
        : `${title} was cancelled.`;
    case 'EVENT_HIDDEN':
      return `${title} was hidden by the moderators. Only you can see it now.${
        reason ? ` Reason: ${reason}` : ''
      }`;
    case 'REPORT_REVIEWED':
      return 'The moderators reviewed a report you sent. Thank you for telling us.';
    case 'REPORT_RECEIVED':
      return 'A new report is waiting for review.';
    case 'EVENT_REMINDER':
      return startsAt
        ? `${title} starts soon, ${formatDeadline(startsAt)}.`
        : `${title} starts soon.`;
    case 'FRIEND_ACCEPTED':
      return 'A friend invite you sent was accepted.';
    default:
      return 'You have a new notification.';
  }
}
