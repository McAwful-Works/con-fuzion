/** The start as a card's time block shows it, in the event's zone: "6:00" over "PM". */
export function startClock(iso: string, zone: string): { time: string; period?: string } {
  const parts = new Intl.DateTimeFormat(undefined, {
    timeZone: zone,
    hour: 'numeric',
    minute: '2-digit',
  }).formatToParts(new Date(iso));
  return {
    time: parts
      .filter((part) => part.type !== 'dayPeriod')
      .map((part) => part.value)
      .join('')
      .trim(),
    // A 24-hour locale has none.
    period: parts.find((part) => part.type === 'dayPeriod')?.value,
  };
}

/** "Saturday" and "Nov 28" for a `dayKey`. Noon UTC keeps the date the same in every time zone. */
export function dayParts(key: string): { weekday: string; date: string } {
  const noon = new Date(`${key}T12:00:00Z`);
  const format = (options: Intl.DateTimeFormatOptions) =>
    new Intl.DateTimeFormat(undefined, { ...options, timeZone: 'UTC' }).format(noon);
  return {
    weekday: format({ weekday: 'long' }),
    date: format({ month: 'short', day: 'numeric' }),
  };
}
