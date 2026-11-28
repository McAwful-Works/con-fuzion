import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  addDays,
  addMonths,
  firstWeekday,
  monthOf,
  monthWeeks,
  noonUtc,
  weekdayOffset,
} from './month';

const format = (day: string, options: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat(undefined, { ...options, timeZone: 'UTC' }).format(noonUtc(day));

const NAV =
  'rounded-control border-control-outline bg-surface-raised flex size-11 shrink-0 items-center justify-center border-2';

interface Props {
  /** The month shown, `2026-11`. */
  month: string;
  onMonthChange: (month: string) => void;
  /** The selected day, `2026-11-29`. */
  selected: string | null;
  onSelect: (day: string) => void;
  /** Today where the viewer is: outlined, and where the Today button goes. */
  today: string;
  /** What to draw under a day's number. Screen readers get `dayLabel` instead. */
  renderDay: (day: string) => ReactNode;
  /** Ends a day's accessible name, after its date: "3 plans". */
  dayLabel: (day: string) => string;
  /** Days that can't be picked. The arrow keys still reach them. */
  isDisabled?: (day: string) => boolean;
  /** From 1280px, tall cells with room for titles. Without it the grid stays compact. */
  titles?: boolean;
  /** Inside the card, under the grid. */
  children?: ReactNode;
}

/**
 * A month of days to pick from, starting each week where the browser's locale does. Arrow keys move
 * between days, Home and End go to the ends of the week, and moving off the month turns to the next.
 */
export function MonthGrid({
  month,
  onMonthChange,
  selected,
  onSelect,
  today,
  renderDay,
  dayLabel,
  isDisabled,
  titles = false,
  children,
}: Props) {
  const headingId = useId();
  const first = firstWeekday(navigator.language);
  const weeks = monthWeeks(month, first);

  // One day takes Tab: the last one moved to, else the selected one, today or the 1st.
  const [cursor, setCursor] = useState<string | null>(null);
  const tabStop =
    [cursor, selected, today].find((day) => day && monthOf(day) === month) ?? `${month}-01`;

  const grid = useRef<HTMLDivElement>(null);
  const focus = (day: string) =>
    grid.current?.querySelector<HTMLElement>(`[data-day="${day}"]`)?.focus();
  // A day to focus once the month it's in has been drawn.
  const pending = useRef<string | null>(null);
  useEffect(() => {
    if (pending.current) focus(pending.current);
    pending.current = null;
  });

  /** Shows the day's month and makes the day the one that takes Tab. */
  function moveTo(day: string) {
    if (monthOf(day) !== month) onMonthChange(monthOf(day));
    setCursor(day);
  }

  /** Moves to a day and focuses it: now, or after the turn to its month. */
  function focusOn(day: string) {
    if (monthOf(day) === month) focus(day);
    else pending.current = day;
    moveTo(day);
  }

  function select(day: string) {
    if (isDisabled?.(day)) return;
    // Turning the month can redraw the button that was clicked, and drop the focus.
    focusOn(day);
    onSelect(day);
  }

  function onKeyDown(event: KeyboardEvent, day: string) {
    // Alt+Left is the browser's Back.
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const into = weekdayOffset(day, first);
    const steps: Record<string, number> = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -7,
      ArrowDown: 7,
      Home: -into,
      End: 6 - into,
    };
    const step = steps[event.key];
    if (step === undefined) return;
    event.preventDefault();
    focusOn(addDays(day, step));
  }

  return (
    <div className="card p-1.5 lg:p-3.5">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-1.5 p-1 lg:p-0">
        <h2 id={headingId} aria-live="polite" className="text-lg font-extrabold">
          {format(`${month}-01`, { month: 'long', year: 'numeric' })}
        </h2>
        <div className="ml-auto flex gap-1.5">
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => {
              moveTo(today);
              if (!isDisabled?.(today)) onSelect(today);
            }}
          >
            Today
          </button>
          <button
            type="button"
            aria-label="Previous month"
            className={NAV}
            onClick={() => onMonthChange(addMonths(month, -1))}
          >
            <ChevronLeft aria-hidden="true" className="size-5" />
          </button>
          <button
            type="button"
            aria-label="Next month"
            className={NAV}
            onClick={() => onMonthChange(addMonths(month, 1))}
          >
            <ChevronRight aria-hidden="true" className="size-5" />
          </button>
        </div>
      </div>
      <div ref={grid} role="grid" aria-labelledby={headingId} className="space-y-0.5">
        <div role="row" className="grid grid-cols-7 gap-0.5">
          {weeks[0]!.map((day) => (
            <span
              key={day}
              role="columnheader"
              aria-label={format(day, { weekday: 'long' })}
              className="text-ink-muted py-1 text-center text-xs font-extrabold"
            >
              <span className={titles ? 'xl:hidden' : undefined}>
                {format(day, { weekday: 'narrow' })}
              </span>
              {titles && <span className="max-xl:hidden">{format(day, { weekday: 'short' })}</span>}
            </span>
          ))}
        </div>
        {weeks.map((week) => (
          <div key={week[0]} role="row" className="grid grid-cols-7 gap-0.5">
            {week.map((day) => {
              const isSelected = day === selected;
              const isToday = day === today;
              const disabled = isDisabled?.(day) ?? false;
              const dimmed = disabled || monthOf(day) !== month;
              return (
                <div key={day} role="gridcell" aria-selected={isSelected} className="flex min-w-0">
                  <button
                    type="button"
                    data-day={day}
                    tabIndex={day === tabStop ? 0 : -1}
                    aria-current={isToday ? 'date' : undefined}
                    // Not `disabled`, which the arrow keys couldn't move onto.
                    aria-disabled={disabled || undefined}
                    aria-label={`${format(day, { dateStyle: 'full' })}, ${dayLabel(day)}`}
                    className={[
                      'rounded-control flex min-h-12 w-full min-w-0 flex-col items-center pt-1.5 text-sm',
                      titles && 'xl:min-h-26 xl:items-stretch xl:p-1.5',
                      dimmed ? 'font-normal' : 'font-bold',
                      isSelected
                        ? 'bg-surface-inverse text-ink-inverse'
                        : dimmed && 'text-ink-muted',
                      // Titles stay legible on a soft fill with a ring, not on the dark one.
                      isSelected && titles && 'xl:bg-surface-sunken xl:text-ink xl:ring-2',
                      isToday
                        ? 'ring-accent-text ring-2 ring-inset'
                        : isSelected && titles && 'xl:ring-ink xl:ring-inset',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onClick={() => select(day)}
                    onKeyDown={(event) => onKeyDown(event, day)}
                  >
                    <span className={titles ? 'xl:px-1 xl:text-left' : undefined}>
                      {Number(day.slice(8))}
                    </span>
                    {renderDay(day)}
                  </button>
                </div>
              );
            })}
          </div>
        ))}
      </div>
      {children}
    </div>
  );
}
