import type { AudienceEntry, AudienceEntryRequest } from '../../api/events';

/** Shown for a server entry the host can no longer use: the API sends `label: null` for it. */
export const UNUSABLE_SERVER_LABEL = 'A server you’re not discoverable in';

/** What identifies an entry: its type plus the one ID it names. Friends has no ID. */
export function entryKey(entry: Pick<AudienceEntry, 'type' | 'guildId' | 'circleId' | 'userId'>) {
  switch (entry.type) {
    case 'FRIENDS':
      return 'FRIENDS';
    case 'GUILD':
      return `GUILD:${entry.guildId}`;
    case 'CIRCLE':
      return `CIRCLE:${entry.circleId}`;
    case 'USER':
      return `USER:${entry.userId}`;
  }
}

export function friendsEntry(): AudienceEntry {
  return { type: 'FRIENDS', guildId: null, circleId: null, userId: null, label: null };
}
export function guildEntry(guildId: string, name: string): AudienceEntry {
  return { type: 'GUILD', guildId, circleId: null, userId: null, label: name };
}
export function circleEntry(circleId: string, name: string): AudienceEntry {
  return { type: 'CIRCLE', guildId: null, circleId, userId: null, label: name };
}
export function userEntry(userId: string, name: string): AudienceEntry {
  return { type: 'USER', guildId: null, circleId: null, userId, label: name };
}

/** The wire form of an entry: only its type and the one ID it names, never the label. */
export function toRequestEntry(entry: AudienceEntry): AudienceEntryRequest {
  switch (entry.type) {
    case 'FRIENDS':
      return { type: 'FRIENDS' };
    case 'GUILD':
      return { type: 'GUILD', guildId: entry.guildId };
    case 'CIRCLE':
      return { type: 'CIRCLE', circleId: entry.circleId };
    case 'USER':
      return { type: 'USER', userId: entry.userId };
  }
}

/** The name to show for an entry. Plain text from the API, never markup. */
export function entryLabel(entry: AudienceEntry): string {
  if (entry.type === 'FRIENDS') return 'Your friends';
  if (entry.label) return entry.label;
  return entry.type === 'GUILD' ? UNUSABLE_SERVER_LABEL : 'Someone you can no longer pick';
}

function list(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** A plain-language sentence about who will be able to see the event. */
export function audienceSummary(entries: AudienceEntry[]): string {
  if (entries.length === 0) return 'Nobody can see this event yet. Choose at least one audience.';
  const parts: string[] = [];
  if (entries.some((e) => e.type === 'FRIENDS')) parts.push('your friends');
  // A server the host isn't discoverable in any more reaches no one (D-012), so it isn't counted.
  const servers = entries.filter((e) => e.type === 'GUILD' && e.label !== null);
  const idle = entries.filter((e) => e.type === 'GUILD' && e.label === null);
  if (servers.length > 0) {
    parts.push(
      `members of ${list(servers.map(entryLabel))} who’ve chosen to be discoverable there`,
    );
  }
  const circles = entries.filter((e) => e.type === 'CIRCLE');
  if (circles.length > 0) {
    const noun = circles.length === 1 ? 'circle' : 'circles';
    parts.push(`people in your ${noun} ${list(circles.map(entryLabel))}`);
  }
  const people = entries.filter((e) => e.type === 'USER');
  if (people.length > 0) parts.push(list(people.map(entryLabel)));
  const note =
    idle.length === 0
      ? ''
      : ` ${idle.length === 1 ? 'A server' : 'Some servers'} you’re not discoverable in won’t reach anyone until you’re discoverable there again.`;
  if (parts.length === 0) return `Only you can see this event as it stands.${note}`;
  return `This event will be visible to ${list(parts)}.${note}`;
}
