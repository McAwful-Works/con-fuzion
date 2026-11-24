const KEY = 'pendingInviteToken';

/**
 * A signed-out visitor's invite token waits here (this tab only) while they sign in. It is only ever
 * used to build an in-app `/invite/<token>` path, so nothing here can send anyone to another site.
 */
export const pendingInvite = {
  get: (): string | null => {
    try {
      return sessionStorage.getItem(KEY);
    } catch {
      return null;
    }
  },
  set: (token: string) => {
    try {
      sessionStorage.setItem(KEY, token);
    } catch {
      // Storage is blocked: the person can open the link again after signing in.
    }
  },
  clear: () => {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      // Nothing was stored.
    }
  },
};

export const invitePath = (token: string) => `/invite/${encodeURIComponent(token)}`;
