import type { Guild } from '../../api/me';

/** The servers you're discoverable in, by name. The API sorts case-sensitively, so sort again. */
export function sortedDiscoverable(guilds: Guild[]): Guild[] {
  return guilds
    .filter((guild) => guild.discoverable)
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
}
