import { Check, Lock, MapPin, Timer, Users, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { HIDDEN_LOCATION_TEXT } from '../events/EventCard';

/**
 * Two made-up meetups that show a signed-out visitor what a card looks like. The text is fixed: the
 * welcome page never asks for real events (D-017). They aren't links, since there is nothing to open.
 */
export function ExampleCards() {
  return (
    <ul aria-label="Example meetups" className="mx-auto flex w-full max-w-md flex-col px-1.5">
      <li className="w-full max-w-80 -rotate-3 self-start">
        <ExampleCard
          time="6:00"
          title="Board game meetup"
          tag="Tabletop"
          location={{ icon: Lock, text: HIDDEN_LOCATION_TEXT }}
          host="Alice"
          avatar="bg-tag text-tag-ink"
        >
          <span className="meter">
            <span aria-hidden="true" className="meter-bar" data-full="">
              <span style={{ width: '100%' }} />
            </span>
            Full
          </span>
          <span className="pill pill-offered">
            <Timer aria-hidden="true" />
            Spot offered
          </span>
        </ExampleCard>
      </li>
      <li className="-mt-1 w-full max-w-80 rotate-[2.5deg] self-end">
        <ExampleCard
          time="3:00"
          title="Cosplay photo walk"
          tag="Photoshoot"
          location={{ icon: MapPin, text: 'Main lobby fountain' }}
          host="Kai"
          avatar="bg-when-3 text-when-ink"
        >
          <span className="meter">
            <Users aria-hidden="true" className="size-4 shrink-0" />
            No limit
          </span>
          <span className="pill pill-going">
            <Check aria-hidden="true" />
            Going
          </span>
        </ExampleCard>
      </li>
    </ul>
  );
}

function ExampleCard({
  time,
  title,
  tag,
  location,
  host,
  avatar,
  children,
}: {
  time: string;
  title: string;
  tag: string;
  location: { icon: LucideIcon; text: string };
  host: string;
  /** The colors of the circle that stands in for the host's avatar. */
  avatar: string;
  /** The spots meter and the status pill. */
  children: ReactNode;
}) {
  const LocationIcon = location.icon;
  return (
    <div className="card flex gap-3 p-3.5">
      <p className="rounded-control bg-when-1 text-when-ink w-15 shrink-0 self-start py-2 text-center">
        <span className="font-head block text-[1.0625rem] leading-tight font-extrabold">
          {time}
        </span>
        <span className="block text-[0.6875rem] font-bold tracking-wider">PM</span>
      </p>
      <div className="flex min-w-0 flex-1 flex-col items-start gap-1.5">
        <span className="tag-chip">{tag}</span>
        <p className="font-head text-[1.0625rem] leading-snug font-extrabold">{title}</p>
        <p className="text-ink-muted flex items-start gap-1.5 text-sm">
          <LocationIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {location.text}
        </p>
        <p className="text-ink-muted flex items-center gap-1.5 text-sm">
          <span
            aria-hidden="true"
            className={`flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-bold ${avatar}`}
          >
            {host.slice(0, 1)}
          </span>
          Hosted by {host} · Test Con
        </p>
        <div className="mt-1 flex w-full flex-wrap items-center justify-between gap-2">
          {children}
        </div>
      </div>
    </div>
  );
}
