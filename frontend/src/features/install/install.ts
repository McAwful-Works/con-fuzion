import { useSyncExternalStore } from 'react';
import { isIos, isStandalone } from '../push/browserPush';

/** Chrome's install prompt event, which TypeScript's DOM types don't include. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<unknown>;
}

// The browser fires `beforeinstallprompt` once, early, so it is caught as soon as this module loads
// (the router imports onboarding) and kept until the Install button is tapped.
let deferred: BeforeInstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((listener) => listener());

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    deferred = event as BeforeInstallPromptEvent;
    emit();
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    emit();
  });
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Whether the browser has offered to install the app, and the way to ask it to. */
export function useInstallPrompt() {
  const available = useSyncExternalStore(subscribe, () => deferred !== null);
  return {
    available,
    async install() {
      const event = deferred;
      if (!event) return;
      // The event works once, and a dismissed browser prompt isn't offered again this visit.
      deferred = null;
      emit();
      try {
        await event.prompt();
      } catch {
        // A stale event or a missing gesture: the browser's own menu can still install it.
      }
    },
  };
}

const DISMISSED_KEY = 'cf-install-hint-dismissed';

/** The one UI flag kept in localStorage for this feature: the install hint was dismissed. */
export const installHintDismissed = {
  get(): boolean {
    try {
      return localStorage.getItem(DISMISSED_KEY) === '1';
    } catch {
      return false;
    }
  },
  set(): void {
    try {
      localStorage.setItem(DISMISSED_KEY, '1');
    } catch {
      // Storage is unavailable; the hint just shows again next time.
    }
  },
};

export type InstallHintKind = 'ios' | 'button';

/**
 * Which install hint to show, if any. iOS Safari has no install prompt, so it gets the Add to Home
 * Screen steps (push needs them there); a browser that offered `beforeinstallprompt` gets a button.
 * Nothing shows once the app is installed or the hint was dismissed.
 */
export function chooseInstallHint(state: {
  ios: boolean;
  standalone: boolean;
  canPrompt: boolean;
  dismissed: boolean;
}): InstallHintKind | null {
  if (state.standalone || state.dismissed) return null;
  if (state.ios) return 'ios';
  return state.canPrompt ? 'button' : null;
}

/** The hint for this browser right now. */
export function useInstallHint(): InstallHintKind | null {
  const { available } = useInstallPrompt();
  return chooseInstallHint({
    ios: isIos(),
    standalone: isStandalone(),
    canPrompt: available,
    dismissed: installHintDismissed.get(),
  });
}
