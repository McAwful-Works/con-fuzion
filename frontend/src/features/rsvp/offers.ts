import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import type { Event } from '../../api/events';
import { useSpots } from '../../api/rsvp';
import { useNow } from './signup';

/**
 * A spot held for you that you can still take. A cancelled event keeps its offers until they run
 * out, but nothing can be accepted (D-014 rule 16).
 */
export function isOpenOffer(event: Event): boolean {
  return event.myStatus === 'OFFERED' && event.status === 'PUBLISHED';
}

/**
 * How long an offered spot is still held, from the deadline `/spots` gives (D-014). `left` is null
 * until that is known. When the offer turns out to be gone, the event lists are asked for again.
 */
export function useOfferCountdown(event: Event): { gone: boolean; left: number | null } {
  const queryClient = useQueryClient();
  const query = useSpots(event);
  const spots = query.data;
  const expiresAt = spots?.myStatus === 'OFFERED' ? spots.offerExpiresAt : null;
  const deadline = expiresAt ? Date.parse(expiresAt) : null;
  const now = useNow(1000, deadline !== null);
  // Ran out, or `/spots` (asked since this appeared) says it's gone: accepted, declined or expired.
  const gone =
    (deadline !== null && deadline <= now) ||
    (query.isFetchedAfterMount && spots !== undefined && spots.myStatus !== 'OFFERED');

  useEffect(() => {
    if (gone) void queryClient.invalidateQueries({ queryKey: ['events'] });
  }, [gone, queryClient]);

  return { gone, left: deadline === null ? null : deadline - now };
}
