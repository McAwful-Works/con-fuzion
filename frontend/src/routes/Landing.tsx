import { Info, Lock, Shield, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { useMe } from '../api/me';
import { SignInLink } from '../features/auth/SignInLink';
import { pendingInvite } from '../features/social/pendingInvite';
import { ExampleCards } from '../features/welcome/ExampleCards';

const HOW_IT_WORKS = [
  {
    title: 'Sign in, pick your servers',
    text: 'Choose which of your Discord servers people can find you in. Everything starts switched off.',
  },
  {
    title: 'Host and choose who sees it',
    text: 'Open a meetup to a server, your friends, a circle, or just a few people. It stays off everyone else’s feed.',
  },
  {
    title: 'Spots fill fairly',
    text: 'First come, first served. If someone leaves, the next person in line gets the spot, usually as an offer to accept.',
  },
];

const PRIVACY = [
  { icon: Lock, text: 'Hide the location until people are confirmed.' },
  { icon: Users, text: 'Only the host and confirmed guests see who’s going.' },
  { icon: Shield, text: 'Block or report anyone. Moderators review every report.' },
];

/**
 * `/welcome`, the front page for a visitor who isn't signed in. Everything on it is fixed text: apart
 * from `/api/me`, which finds a visitor who is already signed in, it makes no request (D-017).
 */
export function Landing() {
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const [hasInvite] = useState(() => pendingInvite.get() !== null);
  // Remember the outcome, then drop `?login=...` so a reload doesn't show it again.
  const [login] = useState(() => params.get('login'));
  useEffect(() => {
    if (!params.has('login')) return;
    const rest = new URLSearchParams(params);
    rest.delete('login');
    setParams(rest, { replace: true });
  }, [params, setParams]);

  if (me.data) return <Navigate to="/" replace />;

  return (
    <div data-welcome-page className="space-y-9 pb-4 lg:space-y-11">
      <div className="grid gap-9 lg:grid-cols-[1.05fr_0.95fr] lg:items-center lg:gap-10 lg:pt-6">
        <section className="flex flex-col items-start gap-4">
          {login === 'failed' && (
            <p role="alert" className="card w-full p-4">
              Sign-in didn’t complete. Please try again.
            </p>
          )}
          {/* D-015 rule 5: the same for a ban and a suspension, and it never mentions reports. */}
          {login === 'refused' && (
            <p role="alert" className="card w-full p-4">
              This account can’t sign in right now.
            </p>
          )}
          {hasInvite && <p className="card w-full p-4">Sign in to answer your friend’s invite.</p>}
          <p className="tag-chip">Free · for convention-goers</p>
          <h1 className="text-[2.5rem] leading-[1.1] font-extrabold lg:text-[4.25rem]">
            Meetups for your {/* Together, so the full stop never wraps onto a line of its own. */}
            <span className="whitespace-nowrap">
              <span className="rounded-control border-outline bg-waiting inline-block -rotate-[1.5deg] border-2 px-1.5">
                con crew
              </span>
              .
            </span>
          </h1>
          <p className="text-ink-muted max-w-lg text-[1.0625rem]">
            Host a meetup, choose exactly who can see it, and let spots fill first come, first
            served.
          </p>
          <SignInLink className="mt-1 min-h-13.5 px-6 text-base" />
          <p className="text-ink-muted flex max-w-lg items-start gap-1.5 text-[0.8125rem]">
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span>
              From Discord we use your name, avatar, the servers you’re in and whether two-factor
              sign-in is on. We never get your email, messages or friends list.
            </span>
          </p>
        </section>
        <ExampleCards />
      </div>

      <section aria-labelledby="how-heading">
        <h2 id="how-heading" className="sr-only">
          How it works
        </h2>
        <ol className="grid gap-3.5 lg:grid-cols-3 lg:gap-5">
          {HOW_IT_WORKS.map((step, i) => (
            <li key={step.title} className="card p-4.5">
              <span
                aria-hidden="true"
                className="rounded-control bg-surface-inverse text-ink-inverse font-head mb-2.5 flex size-9.5 items-center justify-center text-lg font-extrabold"
              >
                {i + 1}
              </span>
              <h3 className="mb-1 text-[1.1875rem] font-extrabold">{step.title}</h3>
              <p className="text-ink-muted text-[0.90625rem]">{step.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section
        aria-labelledby="privacy-heading"
        className="rounded-card border-outline bg-side text-side-ink border-2 p-4.5 lg:px-6 lg:py-5"
      >
        <h2 id="privacy-heading" className="sr-only">
          Your privacy
        </h2>
        <ul className="grid gap-3 lg:grid-cols-3 lg:gap-5">
          {PRIVACY.map(({ icon: Icon, text }) => (
            <li key={text} className="flex items-start gap-2.5 text-[0.90625rem]">
              <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
              {text}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
