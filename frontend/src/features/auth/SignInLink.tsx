import { SIGN_IN_URL } from '../../api/me';

/**
 * "Sign in with Discord", in Discord's blurple with white text. A normal link: sign-in is a full-page
 * redirect through Discord, not an API call.
 */
export function SignInLink({ className = '' }: { className?: string }) {
  return (
    <a href={SIGN_IN_URL} className={`btn bg-discord text-discord-ink ${className}`}>
      Sign in with Discord
    </a>
  );
}
