import { Clock } from 'lucide-react';
import type { Event } from '../../api/events';
import { eventTimes } from './time';
import { useViewerZone } from './useViewerZone';

/** The event's time in its own zone, plus the viewer's zone when it reads differently. */
export function EventTimes({ event }: { event: Pick<Event, 'startsAt' | 'endsAt' | 'timeZone'> }) {
  const times = eventTimes(event, useViewerZone());
  return (
    <p className="flex items-start gap-2">
      <Clock aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>
        <span>{times.event}</span>
        {times.viewer && (
          <span className="text-ink-muted block text-sm">Your time: {times.viewer}</span>
        )}
      </span>
    </p>
  );
}
