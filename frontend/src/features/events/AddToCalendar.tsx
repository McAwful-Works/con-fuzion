import { CalendarPlus, Download, Lock, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { Event } from '../../api/events';
import { useNow } from '../rsvp/signup';
import { endTime, googleCalendarUrl, icsFile, icsFileName } from './ics';

/** Whether a meetup can still go in a calendar: it isn't cancelled and it isn't over. */
export function useCanAddToCalendar(event: Event): boolean {
  const now = useNow(30_000);
  return event.status === 'PUBLISHED' && now < endTime(event);
}

/** Saves the `.ics` file from a Blob: made in the browser, with nothing requested from anywhere. */
function download(event: Event) {
  const file = new Blob([icsFile(event, window.location.origin)], {
    type: 'text/calendar;charset=utf-8',
  });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(file);
  link.download = icsFileName(event.title);
  link.click();
  // Not at once: iOS asks before saving, and reads the Blob only after that.
  setTimeout(() => URL.revokeObjectURL(link.href), 60_000);
}

const CHOICE =
  'rounded-control hover:bg-surface-sunken flex min-h-11 w-full items-center gap-3 px-3 py-2.5 text-left';

function ChoiceText({
  icon: Icon,
  title,
  note,
}: {
  icon: LucideIcon;
  title: string;
  note: string;
}) {
  return (
    <>
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      <span className="min-w-0">
        <span className="block text-sm font-bold">{title}</span>
        <span className="text-ink-muted block text-[0.8125rem]">{note}</span>
      </span>
    </>
  );
}

/**
 * "Add to calendar", for the host and confirmed guests (D-018): the caller decides who gets it. A
 * plain disclosure, like the people menu: Escape, a press elsewhere and tabbing out close it. The
 * choices float above the action bar on phones, and under the button on desktop.
 */
export function AddToCalendar({
  event,
  className = '',
  buttonClassName,
}: {
  event: Event;
  className?: string;
  buttonClassName: string;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPress = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      toggle.current?.focus();
    };
    document.addEventListener('pointerdown', onPress);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPress);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const close = () => {
    toggle.current?.focus();
    setOpen(false);
  };

  return (
    // Positioned on desktop only, so that on phones the choices hang off the whole bar.
    <div
      ref={root}
      className={`lg:relative ${className}`}
      onBlur={(e) => {
        // Safari doesn't focus a pressed button, so only a move to something else counts.
        if (e.relatedTarget && !e.currentTarget.contains(e.relatedTarget)) setOpen(false);
      }}
    >
      <button
        ref={toggle}
        type="button"
        className={`btn w-full ${buttonClassName}`}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <CalendarPlus aria-hidden="true" className="size-4.5 shrink-0" />
        Add to calendar
      </button>
      {open && (
        // The action bar stops clipping while this is in it (`data-calendar-menu`, EventDetail).
        <div
          data-calendar-menu
          className="card divide-line absolute inset-x-4 bottom-full z-20 mb-2 divide-y-2 divide-dashed p-1.5 lg:inset-x-0 lg:top-full lg:bottom-auto lg:mt-2 lg:mb-0"
        >
          <button
            type="button"
            className={CHOICE}
            onClick={() => {
              download(event);
              close();
            }}
          >
            <ChoiceText
              icon={Download}
              title="Download .ics"
              note="Apple Calendar, Outlook, most apps"
            />
          </button>
          <a
            href={googleCalendarUrl(event)}
            target="_blank"
            rel="noopener noreferrer"
            className={CHOICE}
            // After the click has done its work, so the link is still on the page when it does.
            onClick={() => setTimeout(close)}
          >
            <ChoiceText
              icon={CalendarPlus}
              title="Open in Google Calendar"
              note="Adds it from a link"
            />
          </a>
          <p className="text-ink-muted flex items-start gap-3 px-3 py-2.5 text-[0.8125rem]">
            <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span>
              Includes the time and place you can see. Not kept in sync: if the host changes it, add
              it again.
            </span>
          </p>
        </div>
      )}
    </div>
  );
}
