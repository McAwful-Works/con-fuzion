import { useSearchParams } from 'react-router';
import { SEARCH_MAX, SEARCH_MIN, type FeedFilters } from '../../api/events';
import { useGuilds } from '../../api/me';
import { sortedDiscoverable } from './servers';

type Name = keyof FeedFilters;

/**
 * Discover's search and filters, kept in the URL (`?q=`, `guild`, `friends`, `hasSpots`), so that
 * Back from an event returns to the same list.
 */
export function useFeedFilters() {
  const [params, setParams] = useSearchParams();
  const guilds = useGuilds();
  const servers = sortedDiscoverable(guilds.data ?? []);

  const text = params.get('q') ?? '';
  const q = text.trim().slice(0, SEARCH_MAX);
  const guild = params.get('guild');
  // The API answers 400 for any server you aren't discoverable in, so an old link's is left out. If
  // the servers can't be loaded, the API is left to judge.
  const usable = guild && (guilds.isError || servers.some((server) => server.id === guild));

  const filters: FeedFilters = {
    ...(q.length >= SEARCH_MIN && { q }),
    ...(usable && { guild }),
    ...(params.get('friends') === 'true' && { friends: true }),
    ...(params.get('hasSpots') === 'true' && { hasSpots: true }),
  };

  /** Sets or, with `undefined`, removes parameters, without adding to the history. */
  function change(changes: Partial<Record<Name, string | undefined>>) {
    const next = new URLSearchParams(params);
    for (const [name, value] of Object.entries(changes)) {
      if (value) next.set(name, value);
      else next.delete(name);
    }
    setParams(next, { replace: true });
  }

  return {
    filters,
    /** The search as the URL has it, which may be too short to send. */
    text,
    servers,
    /** True until we know whether the URL's server may be asked about. */
    waiting: Boolean(guild) && guilds.isPending,
    change,
    clear: () =>
      change({ q: undefined, guild: undefined, friends: undefined, hasSpots: undefined }),
  };
}
