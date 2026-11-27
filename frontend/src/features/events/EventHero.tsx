import { ChevronLeft, Sparkles } from 'lucide-react';
import { Link } from 'react-router';
import type { Event } from '../../api/events';
import { entryKey, entryLabel } from './audience';

type Reason = NonNullable<Event['visibleBecause']>[number];

/** The viewer's own reasons for seeing an event (D-017): "for Test Con", "Friend of the host", "Invited". */
function reasonTexts(reasons: Reason[]): string[] {
  const servers = reasons.flatMap((r) => (r.type === 'GUILD' && r.guildName ? [r.guildName] : []));
  return [
    ...(servers.length > 0 ? [`for ${new Intl.ListFormat('en').format(servers)}`] : []),
    ...(reasons.some((r) => r.type === 'FRIEND') ? ['Friend of the host'] : []),
    ...(reasons.some((r) => r.type === 'INVITED') ? ['Invited'] : []),
  ];
}

/**
 * The violet band: the tag, the title, the host, and why you can see the event. The host sees the
 * audience instead. Everything from users is plain text. A cover photo can go behind it (#124).
 */
export function EventHero({ event }: { event: Event }) {
  const reasons = reasonTexts(event.visibleBecause ?? []);
  return (
    <header className="bg-hero text-hero-ink border-outline md:rounded-card md:shadow-card relative -mx-4 -mt-6 space-y-3 border-b-2 px-4 py-5 md:mx-0 md:mt-0 md:border-2 lg:p-7">
      {/* Phones have Back in the top bar. */}
      <Link
        to={event.isHost ? '/plans' : '/'}
        className="focus-visible:outline-hero-ink -mt-2 hidden min-h-11 w-fit items-center gap-1 text-sm font-semibold lg:flex"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        {event.isHost ? 'My plans' : 'Discover'}
      </Link>
      {(event.tag || event.isHost) && (
        <div className="flex flex-wrap items-center gap-2">
          {event.tag && (
            <span className="tag-chip bg-hero-tag text-hero-ink break-all">{event.tag}</span>
          )}
          {event.isHost && (
            <span className="pill pill-hosting">
              <Sparkles aria-hidden="true" />
              You’re hosting
            </span>
          )}
        </div>
      )}
      <h1 className="text-3xl leading-tight font-extrabold break-words lg:text-[2.5rem]">
        {event.title}
      </h1>
      <div className="flex items-center gap-2.5 text-sm">
        <img
          src={event.host.avatarUrl}
          alt=""
          className="border-hero-ink size-9 shrink-0 rounded-full border-2"
        />
        {event.isHost ? (
          event.audience && (
            <section aria-label="Who can see this" className="flex min-w-0 flex-wrap gap-1.5">
              <span className="py-1">For</span>
              <ul className="contents">
                {event.audience.map((entry) => (
                  <li
                    key={entryKey(entry)}
                    className="bg-hero-tag rounded-control min-w-0 px-2.5 py-1 font-semibold break-words"
                  >
                    {entryLabel(entry)}
                  </li>
                ))}
              </ul>
            </section>
          )
        ) : (
          <p className="min-w-0 break-words">
            <span className="font-semibold">Hosted by {event.host.displayName}</span>
            {reasons.length > 0 && <span> · {reasons.join(' · ')}</span>}
          </p>
        )}
      </div>
    </header>
  );
}
