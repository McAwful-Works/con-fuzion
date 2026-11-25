import { useEffect, useState } from 'react';

/** Plan B2: the unlock is delayed by a random 0 to 1.5 seconds so a crowd doesn't all tap at once. */
export const JITTER_MAX_MS = 1500;

/** `setTimeout` fires at once for a delay above 2^31 - 1 ms (about 24.8 days); signup can open a year out. */
const MAX_TIMEOUT_MS = 2 ** 31 - 1;

/** The current time, refreshed every `intervalMs` while `active`. */
export function useNow(intervalMs: number, active = true): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs, active]);
  return now;
}

/**
 * Whether the Join button may be pressed. With no signup time, or one that has already passed when
 * the page opens, that is immediate. Otherwise it unlocks once the opening time arrives plus a random
 * 0 to 1.5 seconds. Give the component using this a `key` of the signup time, so a changed time resets it.
 */
export function useSignupUnlocked(signupOpensAt: string | null): boolean {
  const [unlocked, setUnlocked] = useState(
    () => !signupOpensAt || Date.parse(signupOpensAt) <= Date.now(),
  );
  // Bumped to arm the timer again when one wait wasn't enough (see MAX_TIMEOUT_MS).
  const [round, setRound] = useState(0);
  useEffect(() => {
    if (unlocked || !signupOpensAt) return;
    const opensAt = Date.parse(signupOpensAt);
    const wait = Math.max(0, opensAt - Date.now()) + Math.random() * JITTER_MAX_MS;
    const id = setTimeout(
      () => (Date.now() >= opensAt ? setUnlocked(true) : setRound((r) => r + 1)),
      Math.min(wait, MAX_TIMEOUT_MS),
    );
    return () => clearTimeout(id);
  }, [unlocked, signupOpensAt, round]);
  return unlocked;
}

/** "1d 2h 05m 09s", "12m 03s" or "9s": the time left, rounded up to the second. */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const two = (n: number) => String(n).padStart(2, '0');
  if (d > 0) return `${d}d ${h}h ${two(m)}m ${two(s)}s`;
  if (h > 0) return `${h}h ${two(m)}m ${two(s)}s`;
  if (m > 0) return `${m}m ${two(s)}s`;
  return `${s}s`;
}
