import { Clock } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { usePlans, type Event } from '../../api/events';
import { monthOf } from '../../components/month';
import { MonthGrid } from '../../components/MonthGrid';
import { dayParts } from './cardTime';
import { Field, FieldError } from './formFields';
import { dayHeading, dayKey, isValidTimeZone } from './time';
import { useViewerZone } from './useViewerZone';
import type { SectionProps } from './validation';

/** The quick times: 10 AM to 8 PM, every 2 hours. */
const QUICK = ['10:00', '12:00', '14:00', '16:00', '18:00', '20:00'];

const CHIP =
  'rounded-control border-control-outline bg-surface-raised inline-flex min-h-11 items-center gap-1.5 border-2 px-3 text-sm font-bold aria-pressed:border-transparent aria-pressed:bg-surface-inverse aria-pressed:text-ink-inverse';

interface Picked {
  day: string | null;
  time: string | null;
}

/** The day and time in a `datetime-local` value. Years the grid can't draw count as neither. */
function split(local: string): Picked {
  const match = /^((?:19|2\d)\d\d-\d\d-\d\d)T(\d\d:\d\d)/.exec(local);
  return { day: match?.[1] ?? null, time: match?.[2] ?? null };
}

function clock(iso: string, timeZone: string, withZoneName = false): string {
  return new Intl.DateTimeFormat(undefined, {
    timeZone,
    hour: 'numeric',
    minute: '2-digit',
    ...(withZoneName && { timeZoneName: 'short' }),
  }).format(new Date(iso));
}

/** How one of your plans is marked: its dot, and in words unless you're simply going. */
function mark(plan: Event): { dot: string; note?: string } {
  if (plan.isHost) return { dot: 'bg-dot-hosting', note: 'hosting' };
  if (plan.myStatus === 'OFFERED') return { dot: 'bg-dot-offer', note: 'spot offered' };
  if (plan.myStatus === 'WAITLISTED') return { dot: 'bg-dot-waiting', note: 'waitlisted' };
  return { dot: 'bg-dot-going' };
}

const Dot = ({ plan, className = '' }: { plan: Event; className?: string }) => (
  <span
    aria-hidden="true"
    data-dot={plan.id}
    className={`size-1.75 shrink-0 rounded-full ${mark(plan).dot} ${className}`}
  />
);

/**
 * When an event starts: a day picked on a month grid that dots your own plans, then a quick time,
 * or "Other…" for the date and time field. All of it writes the same `datetime-local` text, read in
 * the event's zone. `children` go in the column beside the grid.
 */
export function StartPicker({
  values,
  errors,
  set,
  event,
  children,
}: SectionProps & { event?: Event; children: ReactNode }) {
  const timeId = useId();
  const agendaId = useId();
  const viewerZone = useViewerZone();
  const zone = isValidTimeZone(values.timeZone) ? values.timeZone : viewerZone;
  const [now] = useState(() => new Date().toISOString());
  // Where the event is: a day is past once it's over there.
  const today = dayKey(now, zone);
  // An edit may keep a start that has passed, so its day can always be picked again.
  const original = event && dayKey(event.startsAt, event.timeZone);

  // A day or a time picked alone, which the form can't hold yet.
  const [draft, setDraft] = useState<Picked>({ day: null, time: null });
  const { day, time } = values.startsAt ? split(values.startsAt) : draft;
  const [month, setMonth] = useState(() => monthOf(day ?? today));
  const [other, setOther] = useState(() => time !== null && !QUICK.includes(time));

  function pick(next: Picked) {
    setDraft(next);
    set('startsAt', next.day && next.time ? `${next.day}T${next.time}` : '');
  }

  function type(value: string) {
    const typed = split(value);
    setDraft(typed);
    if (typed.day) setMonth(monthOf(typed.day));
    set('startsAt', value);
  }

  // Your own plans, by the day they start on where you are (D-018), without the one being edited.
  const plans = usePlans();
  const byDay = new Map<string, Event[]>();
  for (const plan of plans.data ?? []) {
    if (plan.status === 'CANCELLED' || plan.id === event?.id) continue;
    const key = dayKey(plan.startsAt, viewerZone);
    byDay.set(key, [...(byDay.get(key) ?? []), plan]);
  }
  const on = (key: string) => byDay.get(key) ?? [];
  const agenda = day ? on(day) : [];
  const parts = day ? dayParts(day) : null;

  return (
    <fieldset className="min-w-0">
      <legend className="field-label mb-1.5">Starts</legend>
      <div className="@container">
        <div className="grid items-start gap-4 @2xl:grid-cols-[22.5rem_minmax(0,1fr)]">
          <MonthGrid
            month={month}
            onMonthChange={setMonth}
            selected={day}
            onSelect={(picked) => pick({ day: picked, time })}
            today={today}
            isDisabled={(key) => key < today && key !== original}
            dayLabel={(key) => {
              const count = on(key).length;
              return count === 0 ? 'no plans' : `${count} plan${count === 1 ? '' : 's'}`;
            }}
            renderDay={(key) => (
              <span className="mt-1 flex gap-0.75">
                {on(key)
                  .slice(0, 3)
                  .map((p) => (
                    <Dot key={p.id} plan={p} />
                  ))}
              </span>
            )}
          >
            <p className="hint px-1.5 pt-2 pb-1">
              {plans.isError
                ? 'Your plans couldn’t load, so they aren’t shown.'
                : 'Dots are your own plans.'}{' '}
              Past days can’t be picked.
            </p>
          </MonthGrid>

          <div className="min-w-0 space-y-4">
            <div className="space-y-2">
              <p id={timeId} className="field-label">
                {parts ? `${parts.weekday}, ${parts.date} · start time` : 'Start time'}
              </p>
              <div role="group" aria-labelledby={timeId} className="flex flex-wrap gap-2">
                {QUICK.map((quick) => (
                  <button
                    key={quick}
                    type="button"
                    className={CHIP}
                    aria-pressed={!other && quick === time}
                    onClick={() => {
                      setOther(false);
                      pick({ day, time: quick });
                    }}
                  >
                    {clock(`1970-01-01T${quick}:00Z`, 'UTC')}
                  </button>
                ))}
                <button
                  type="button"
                  className={CHIP}
                  aria-pressed={other}
                  onClick={() => setOther(true)}
                >
                  <Clock aria-hidden="true" className="size-4" />
                  Other…
                </button>
              </div>
              {other ? (
                <Field label="Starts" error={errors.startsAt}>
                  {(p) => (
                    <input
                      {...p}
                      type="datetime-local"
                      value={values.startsAt}
                      onChange={(e) => type(e.target.value)}
                    />
                  )}
                </Field>
              ) : (
                <FieldError>{errors.startsAt}</FieldError>
              )}
            </div>

            {agenda.length > 0 && (
              <section
                aria-labelledby={agendaId}
                className="rounded-card border-control-outline bg-surface-sunken space-y-1.5 border-2 px-3.5 py-3"
              >
                <h3 id={agendaId} className="text-sm font-extrabold">
                  Already that day
                </h3>
                <ul className="space-y-1 text-sm">
                  {agenda.map((p) => {
                    // The plan's own date and zone, where they differ from what's being picked.
                    const ownDay = dayKey(p.startsAt, p.timeZone);
                    const { note } = mark(p);
                    return (
                      <li key={p.id} className="flex gap-2">
                        <Dot plan={p} className="mt-1.5" />
                        <span className="min-w-0 break-words">
                          {ownDay !== day && `${dayHeading(ownDay)} · `}
                          {clock(p.startsAt, p.timeZone, p.timeZone !== zone)} · {p.title}{' '}
                          {note && <span className="text-ink-muted">({note})</span>}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </section>
            )}
            {children}
          </div>
        </div>
      </div>
    </fieldset>
  );
}
