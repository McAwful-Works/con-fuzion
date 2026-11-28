// Calendar days and months as text: a day is a `dayKey` such as `2026-11-29`, a month `2026-11`.

/** Noon UTC, so that adding days or reading the weekday never lands on a neighbouring date. */
export const noonUtc = (day: string) => new Date(`${day}T12:00:00Z`);

export const monthOf = (day: string) => day.slice(0, 7);

export function addDays(day: string, count: number): string {
  const date = noonUtc(day);
  date.setUTCDate(date.getUTCDate() + count);
  return date.toISOString().slice(0, 10);
}

export function addMonths(month: string, count: number): string {
  const date = noonUtc(`${month}-01`);
  date.setUTCMonth(date.getUTCMonth() + count);
  return date.toISOString().slice(0, 7);
}

/** `firstDay` runs from 1, Monday, to 7, Sunday. */
interface WeekInfo {
  firstDay: number;
}

/**
 * The day the locale's week starts on, from 0 for Sunday to 6. Sunday where the browser can't say.
 */
export function firstWeekday(locale: string): number {
  try {
    // Not in TypeScript's types yet. Browsers have the method, an older property, or neither.
    const known = new Intl.Locale(locale) as Intl.Locale & {
      getWeekInfo?: () => WeekInfo;
      weekInfo?: WeekInfo;
    };
    const info = known.getWeekInfo?.() ?? known.weekInfo;
    return info ? info.firstDay % 7 : 0;
  } catch {
    return 0;
  }
}

/** How far into its week a day is: 0 on the week's first day, 6 on its last. */
export function weekdayOffset(day: string, first: number): number {
  return (noonUtc(day).getUTCDay() - first + 7) % 7;
}

/** The weeks that show a month, each of seven days, filled out with the neighbouring months'. */
export function monthWeeks(month: string, first: number): string[][] {
  const weeks: string[][] = [];
  let start = addDays(`${month}-01`, -weekdayOffset(`${month}-01`, first));
  do {
    weeks.push(Array.from({ length: 7 }, (_, i) => addDays(start, i)));
    start = addDays(start, 7);
  } while (monthOf(start) === month);
  return weeks;
}
