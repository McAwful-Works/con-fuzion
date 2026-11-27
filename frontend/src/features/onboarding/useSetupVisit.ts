import { useLocation, useMatch } from 'react-router';

/**
 * Whether this is the setup that follows the first sign-in. The server sends a new user to
 * `/onboarding` with a page load, and React Router calls the first entry of a visit `default`. A link
 * inside the app is a return, for the servers step alone: the audience picker's today, and You →
 * Servers once it has one. Opening that link in a new tab is a page load, so it shows the setup.
 */
export function useSetupVisit(): boolean {
  const onOnboarding = useMatch('/onboarding') !== null;
  const { key } = useLocation();
  return onOnboarding && key === 'default';
}
