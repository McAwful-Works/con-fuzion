import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { addMonths, firstWeekday, monthWeeks } from './month';
import { MonthGrid } from './MonthGrid';

const TODAY = '2026-11-26';

/** The grid as a page would hold it, with something under one day and a label for each. */
function Harness({ picked, onSelect }: { picked: string | null; onSelect: (day: string) => void }) {
  const [month, setMonth] = useState('2026-11');
  const [selected, setSelected] = useState(picked);
  return (
    <MonthGrid
      month={month}
      onMonthChange={setMonth}
      selected={selected}
      onSelect={(day) => {
        setSelected(day);
        onSelect(day);
      }}
      today={TODAY}
      renderDay={(day) => day === '2026-11-28' && <span>busy</span>}
      dayLabel={(day) => (day === '2026-11-28' ? '2 plans' : 'no plans')}
    >
      <p>under the grid</p>
    </MonthGrid>
  );
}

function show(picked: string | null = '2026-11-28', locale = 'en-US') {
  vi.spyOn(navigator, 'language', 'get').mockReturnValue(locale);
  const onSelect = vi.fn();
  render(<Harness picked={picked} onSelect={onSelect} />);
  return onSelect;
}

const grid = () => screen.getByRole('grid');
const monthName = () =>
  document.getElementById(grid().getAttribute('aria-labelledby')!)!.textContent;
/** A day's button, by its date: "November 29". */
const day = (date: string) => screen.getByRole('button', { name: new RegExp(` ${date}, 202\\d,`) });
const press = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const pickedDays = () =>
  screen
    .queryAllByRole('gridcell', { selected: true })
    .map((cell) => within(cell).getByRole('button').dataset.day);
const tabStops = () =>
  [...grid().querySelectorAll<HTMLElement>('button[tabindex="0"]')].map((b) => b.dataset.day);

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('month days', () => {
  it('starts the week where the locale does, and on Sunday when the browser can’t say', () => {
    expect(firstWeekday('en-US')).toBe(0);
    expect(firstWeekday('en-GB')).toBe(1);
    expect(firstWeekday('not a locale')).toBe(0);
    vi.stubGlobal('Intl', Object.assign(Object.create(Intl), { Locale: class {} }));
    expect(firstWeekday('en-GB')).toBe(0);
  });

  it('fills out the weeks with the neighbouring months', () => {
    const sunday = monthWeeks('2026-11', 0);
    expect(sunday).toHaveLength(5);
    expect([sunday[0]![0], sunday[4]![6]]).toEqual(['2026-11-01', '2026-12-05']);
    const monday = monthWeeks('2026-11', 1);
    expect(monday).toHaveLength(6);
    expect([monday[0]![0], monday[5]![6]]).toEqual(['2026-10-26', '2026-12-06']);
    // Four weeks exactly.
    expect(monthWeeks('2026-02', 0)).toHaveLength(4);
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(addMonths('2027-01', -1)).toBe('2026-12');
  });
});

describe('MonthGrid', () => {
  it.each([
    ['en-US', 'Sunday', '2026-11-01'],
    ['en-GB', 'Monday', '2026-10-26'],
  ])('starts the week for %s on %s', (locale, name, first) => {
    show(null, locale);

    expect(within(grid()).getAllByRole('columnheader')[0]).toHaveAccessibleName(name);
    expect(grid().querySelector('button')).toHaveAttribute('data-day', first);
  });

  it('names each day by its date and label, and marks today and the picked day', () => {
    show();

    expect(monthName()).toBe('November 2026');
    expect(day('November 28')).toHaveAccessibleName('Saturday, November 28, 2026, 2 plans');
    expect(day('November 28')).toHaveTextContent('28busy');
    expect(day('December 2')).toHaveAccessibleName('Wednesday, December 2, 2026, no plans');
    expect(day('November 26')).toHaveAttribute('aria-current', 'date');
    expect(day('November 28')).not.toHaveAttribute('aria-current');
    expect(pickedDays()).toEqual(['2026-11-28']);
    expect(screen.getByText('under the grid')).toBeInTheDocument();
  });

  it('gives Tab to today, or in a month without it to the 1st, while nothing is picked', () => {
    show(null);

    expect(pickedDays()).toEqual([]);
    expect(tabStops()).toEqual(['2026-11-26']);
    press('Next month');
    expect(tabStops()).toEqual(['2026-12-01']);
  });

  it('turns the month without picking, and Today comes back and picks today', () => {
    const onSelect = show();

    press('Previous month');
    expect(monthName()).toBe('October 2026');
    press('Next month');
    press('Next month');
    expect(monthName()).toBe('December 2026');
    expect(onSelect).not.toHaveBeenCalled();

    press('Today');
    expect(monthName()).toBe('November 2026');
    expect(pickedDays()).toEqual(['2026-11-26']);
    expect(onSelect).toHaveBeenLastCalledWith('2026-11-26');
  });

  it('picks a day, turning to its month and keeping the focus on it', () => {
    const onSelect = show();

    fireEvent.click(day('November 27'));
    expect(onSelect).toHaveBeenLastCalledWith('2026-11-27');
    expect(pickedDays()).toEqual(['2026-11-27']);
    expect(tabStops()).toEqual(['2026-11-27']);

    fireEvent.click(day('December 2'));
    expect(monthName()).toBe('December 2026');
    expect(pickedDays()).toEqual(['2026-12-02']);
    expect(day('December 2')).toHaveFocus();
  });

  it('moves between days with the arrow keys, turning the month, without picking', () => {
    const onSelect = show();
    const move = (key: string, expected: string) => {
      fireEvent.keyDown(document.activeElement!, { key });
      expect(document.activeElement).toHaveAttribute('data-day', expected);
      expect(tabStops()).toEqual([expected]);
    };
    expect(tabStops()).toEqual(['2026-11-28']);
    day('November 28').focus();

    move('ArrowRight', '2026-11-29');
    move('ArrowDown', '2026-12-06');
    expect(monthName()).toBe('December 2026');
    move('ArrowUp', '2026-11-29');
    expect(monthName()).toBe('November 2026');
    move('ArrowLeft', '2026-11-28');
    expect(onSelect).not.toHaveBeenCalled();
    // The browser's own shortcuts are left alone.
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowLeft', altKey: true });
    expect(document.activeElement).toHaveAttribute('data-day', '2026-11-28');
  });

  it('leaves the focus where it goes after a key that leads nowhere', () => {
    show(null);
    fireEvent.click(day('November 29'));
    day('November 29').focus();
    // A Sunday, so already the first day of its week. Twice, so React has nothing to redraw.
    fireEvent.keyDown(day('November 29'), { key: 'Home' });
    fireEvent.keyDown(day('November 29'), { key: 'Home' });
    const next = screen.getByRole('button', { name: 'Next month' });
    next.focus();
    // December shows November 29 too.
    fireEvent.click(next);

    expect(next).toHaveFocus();
  });

  it.each([
    ['en-US', '2026-11-22', '2026-11-28'],
    ['en-GB', '2026-11-23', '2026-11-29'],
  ])('goes to the ends of the week with Home and End in %s', (locale, first, last) => {
    show('2026-11-28', locale);

    fireEvent.keyDown(day('November 28'), { key: 'Home' });
    expect(document.activeElement).toHaveAttribute('data-day', first);
    fireEvent.keyDown(document.activeElement!, { key: 'End' });
    expect(document.activeElement).toHaveAttribute('data-day', last);
  });
});
