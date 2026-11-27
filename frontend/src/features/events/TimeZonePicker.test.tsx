import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { TimeZonePicker } from './TimeZonePicker';

const NEW_YORK = /^America\/New_York · Eastern \(E[SD]T\)$/;

/** The picker inside a form, holding its own value as the event form does. */
function setup(initial = 'America/Chicago') {
  const onChange = vi.fn();
  const onSubmit = vi.fn();
  function Host() {
    const [value, setValue] = useState(initial);
    return (
      <form onSubmit={onSubmit}>
        <TimeZonePicker
          value={value}
          onChange={(zone) => {
            onChange(zone);
            setValue(zone);
          }}
          hint="Search by city or zone."
        />
      </form>
    );
  }
  render(<Host />);
  const box = screen.getByRole('combobox', { name: 'Time zone' }) as HTMLInputElement;
  const type = (text: string) => fireEvent.change(box, { target: { value: text } });
  /** False when the picker kept the key's default, such as submitting the form. */
  const press = (key: string) => fireEvent.keyDown(box, { key });
  const options = () => screen.queryAllByRole('option');
  const activeOption = () =>
    document.getElementById(box.getAttribute('aria-activedescendant') ?? '');
  return { box, type, press, options, activeOption, onChange, onSubmit };
}

describe('the time-zone picker', () => {
  it('shows the chosen zone with its common name and abbreviation, closed', () => {
    const { box, options } = setup('America/New_York');

    expect(box.value).toMatch(NEW_YORK);
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box).not.toHaveAttribute('aria-activedescendant');
    expect(box).toHaveAccessibleDescription('Search by city or zone.');
    expect(options()).toHaveLength(0);
  });

  it('opens on the chosen zone, with offsets, and draws only part of the long list', () => {
    const { box, options, activeOption } = setup('America/New_York');

    fireEvent.click(box);

    expect(box).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('listbox')).toHaveAttribute('id', box.getAttribute('aria-controls'));
    expect(options()).toHaveLength(50);
    expect(options()[0]).toHaveTextContent(/America\/New_York · Eastern \(E[SD]T\)/);
    expect(options()[0]).toHaveTextContent(/GMT-0[45]:00/);
    expect(options()[0]).toHaveTextContent('Current choice');
    expect(options()[1]).toHaveTextContent('UTC · Coordinated Universal Time');
    expect(activeOption()).toBe(options()[0]);
    expect(screen.getByText(/Showing 50 of \d+\./)).toBeInTheDocument();
  });

  it.each([
    ['new york', /America\/New_York/],
    ['AMERICA/DENVER', /America\/Denver · Mountain/],
    ['los_angeles', /America\/Los_Angeles/],
    ['eastern new', /America\/New_York/],
    ['kolkata', /Asia\/(Calcutta|Kolkata)/],
    ['kyiv', /Europe\/(Kiev|Kyiv)/],
    ['utc', /^UTC · Coordinated Universal Time/],
  ])('finds a zone by “%s”', (query, expected) => {
    const { type, options } = setup();

    type(query);

    expect(options()[0]).toHaveTextContent(expected);
  });

  it('lists cities that start with the search before other matches', () => {
    const { type, options } = setup();

    type('york');
    expect(options()[0]).toHaveTextContent('America/New_York');

    type('lond');
    expect(options()[0]).toHaveTextContent('Europe/London');
  });

  it('says so when nothing matches, and keeps the zone', () => {
    const { box, type, press, options, onChange, onSubmit } = setup();

    type('atlantis');

    expect(options()).toHaveLength(0);
    expect(screen.getByRole('status')).toHaveTextContent('No time zone matches.');
    expect(box).not.toHaveAttribute('aria-activedescendant');
    // Enter chooses nothing, and still doesn't submit the form.
    expect(press('Enter')).toBe(false);
    press('ArrowDown');
    expect(onChange).not.toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('announces how many zones match', () => {
    const { type } = setup();
    type('new york');
    expect(screen.getByRole('status')).toHaveTextContent('1 time zone available.');
  });

  it('is chosen from with the keyboard alone', () => {
    const { box, type, press, options, activeOption, onChange } = setup();

    expect(press('ArrowDown')).toBe(false);
    expect(box).toHaveAttribute('aria-expanded', 'true');
    type('europe/b');
    expect(activeOption()).toBe(options()[0]);

    press('ArrowDown');
    press('ArrowDown');
    press('ArrowUp');
    expect(activeOption()).toBe(options()[1]);
    expect(options()[1]).toHaveAttribute('aria-selected', 'true');
    expect(options()[0]).toHaveAttribute('aria-selected', 'false');
    const second = options()[1]!.textContent!.split(' · ')[0]!.split(' (')[0]!;

    expect(press('Enter')).toBe(false);

    expect(onChange).toHaveBeenCalledExactlyOnceWith(second);
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box.value.startsWith(second)).toBe(true);
  });

  it('stops at both ends of the list', () => {
    const { type, press, options, activeOption } = setup();
    type('new york');
    press('ArrowUp');
    press('ArrowDown');
    press('ArrowDown');
    expect(activeOption()).toBe(options()[0]);
  });

  it('puts the zone back on Escape and on leaving the field', () => {
    const { box, type, press, onChange } = setup('America/New_York');

    type('tok');
    expect(press('Escape')).toBe(false);
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box.value).toMatch(NEW_YORK);

    type('tok');
    fireEvent.blur(box);
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box.value).toMatch(NEW_YORK);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('leaves Enter and Escape alone while closed', () => {
    const { press } = setup();
    expect(press('Enter')).toBe(true);
    expect(press('Escape')).toBe(true);
  });

  it('selects the zone’s text, so that typing replaces it', () => {
    const { box, type, press } = setup();

    act(() => box.focus());
    expect([box.selectionStart, box.selectionEnd]).toEqual([0, box.value.length]);

    type('tokyo');
    press('Enter');
    expect(box.value).toMatch(/^Asia\/Tokyo/);
    expect([box.selectionStart, box.selectionEnd]).toEqual([0, box.value.length]);
  });

  it('chooses with a tap, without the tap first closing the list', () => {
    const { box, type, options, onChange } = setup();
    type('tokyo');

    // A prevented mousedown keeps focus in the input.
    expect(fireEvent.mouseDown(options()[0]!)).toBe(false);
    fireEvent.click(options()[0]!);

    expect(onChange).toHaveBeenCalledExactlyOnceWith('Asia/Tokyo');
    expect(box).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows a zone the browser’s list spells differently', () => {
    const { box, options } = setup('Etc/GMT+5');
    expect(box.value).toMatch(/^Etc\/GMT\+5/);
    fireEvent.click(box);
    expect(options()[0]).toHaveTextContent('Etc/GMT+5');
  });

  it('ties an error to the field', () => {
    render(<TimeZonePicker value="UTC" onChange={() => {}} error="Choose another." />);
    const box = screen.getByRole('combobox');
    expect(box).toHaveAttribute('aria-invalid', 'true');
    expect(box).toHaveAccessibleDescription('Choose another.');
    expect(screen.getByRole('alert')).toHaveTextContent('Choose another.');
  });
});
