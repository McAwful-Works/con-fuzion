import { Check, ChevronDown, Globe } from 'lucide-react';
import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import {
  knownTimeZones,
  prepareTimeZoneSearch,
  searchTimeZones,
  timeZoneLabel,
  timeZoneOffset,
} from './time';

/** The most options drawn at once. Each one costs a few `Intl` lookups, which adds up on phones. */
const SHOWN = 50;

interface Props {
  label?: string;
  /** The chosen IANA zone name. */
  value: string;
  onChange: (zone: string) => void;
  hint?: string;
  error?: string;
}

/**
 * A searchable list of time zones: an ARIA 1.2 combobox with a listbox popup. Focus stays in the
 * input; the arrow keys move through the list, Enter or a tap chooses, and Escape or leaving the
 * field keeps the zone as it was, so the value is always a zone from the list.
 */
export function TimeZonePicker({ label = 'Time zone', value, onChange, hint, error }: Props) {
  const id = useId();
  const listId = `${id}-list`;
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const optionId = (index: number) => `${id}-option-${index}`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const [open, setOpen] = useState(false);
  /** What has been typed, or null while the field shows the chosen zone. */
  const [query, setQuery] = useState<string | null>(null);
  const [active, setActive] = useState(0);

  // With nothing typed, the chosen zone leads the list.
  const matches = !open
    ? []
    : query?.trim()
      ? searchTimeZones(knownTimeZones(), query)
      : [value, ...knownTimeZones().filter((zone) => zone !== value)].filter(Boolean);
  const shown = matches.slice(0, SHOWN);

  const show = () => {
    setOpen(true);
    setActive(0);
  };
  const close = () => {
    setOpen(false);
    setQuery(null);
  };
  const choose = (zone: string) => {
    onChange(zone);
    close();
  };

  // The shown zone is selected while the field has focus, so typing replaces it with a search.
  useLayoutEffect(() => {
    if (query === null && document.activeElement === input.current) input.current?.select();
  }, [query, value, open]);

  useEffect(() => (open ? prepareTimeZoneSearch() : undefined), [open]);

  useEffect(() => {
    if (!open) return;
    // Optional call: jsdom has no scrollIntoView.
    list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' });
  }, [open, active, query]);

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) return show();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      setActive((index) => Math.max(0, Math.min(index + step, shown.length - 1)));
    } else if (e.key === 'Enter' && open) {
      // Choosing a zone must not submit the form around the picker.
      e.preventDefault();
      const zone = shown[active];
      if (zone) choose(zone);
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      close();
    }
  };

  return (
    <div className="field">
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      <div className="relative">
        <Globe
          aria-hidden
          className="text-ink-muted pointer-events-none absolute top-3.5 left-3 size-5"
        />
        <input
          ref={input}
          id={id}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && shown[active] ? optionId(active) : undefined}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy || undefined}
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          value={query ?? timeZoneLabel(value)}
          onChange={(e) => {
            setQuery(e.target.value);
            show();
          }}
          onFocus={(e) => e.target.select()}
          onClick={(e) => {
            if (open) return;
            e.currentTarget.select();
            show();
          }}
          onBlur={close}
          onKeyDown={onKeyDown}
          className="field-control pr-9 pl-10 text-ellipsis"
        />
        <ChevronDown
          aria-hidden
          className="text-ink-muted pointer-events-none absolute top-3.5 right-3 size-5"
        />
        {/* Keeping the mouse button's default away keeps focus, and the list, in place. */}
        <div
          hidden={!open}
          onMouseDown={(e) => e.preventDefault()}
          className="card absolute inset-x-0 top-full z-20 mt-1.5 overflow-hidden"
        >
          <ul
            ref={list}
            id={listId}
            role="listbox"
            aria-label={`${label} options`}
            className="max-h-72 overflow-y-auto p-1"
          >
            {shown.map((zone, index) => (
              <li
                key={zone}
                id={optionId(index)}
                role="option"
                aria-selected={index === active}
                onClick={() => choose(zone)}
                className="rounded-control aria-selected:bg-surface-sunken aria-selected:outline-focus flex min-h-11 cursor-pointer items-center gap-3 px-2.5 py-1.5 aria-selected:outline-2 aria-selected:-outline-offset-2"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold break-words">
                    {timeZoneLabel(zone)}
                  </span>
                  <span className="hint block">{timeZoneOffset(zone)}</span>
                </span>
                {zone === value && (
                  <>
                    <Check aria-hidden className="size-5 shrink-0" />
                    <span className="sr-only">Current choice</span>
                  </>
                )}
              </li>
            ))}
          </ul>
          {open && matches.length === 0 && (
            <p className="px-3.5 py-3 text-sm">
              No time zone matches. Try a city, such as Chicago.
            </p>
          )}
          {matches.length > shown.length && (
            <p className="hint border-line border-t px-3.5 py-2">
              Showing {shown.length} of {matches.length}. Type a city to narrow the list.
            </p>
          )}
        </div>
      </div>
      {/* Says how many zones match as the search changes. */}
      <p role="status" className="sr-only">
        {!open
          ? ''
          : matches.length === 0
            ? 'No time zone matches.'
            : `${matches.length} time zone${matches.length === 1 ? '' : 's'} available.`}
      </p>
      {hint && (
        <p id={hintId} className="hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-sm font-medium">
          {error}
        </p>
      )}
    </div>
  );
}
