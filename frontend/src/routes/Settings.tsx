import { Ban, Globe, Info, LogOut, MonitorSmartphone } from 'lucide-react';
import { useNavigate } from 'react-router';
import { useGuilds, useMe, useSetTimeZone, useSignOut } from '../api/me';
import { useBlocks } from '../api/social';
import { ErrorState } from '../components/ErrorState';
import { TimeZonePicker } from '../features/events/TimeZonePicker';
import { useViewerZone } from '../features/events/useViewerZone';
import { AppearanceChoice, InstallRow, PushRow } from '../features/you/DeviceSettings';
import { ButtonRow, Group, GroupHeading, LinkRow, RowLabel } from '../features/you/rows';

/** `/you`: who you're signed in as, and your settings. */
export function Settings() {
  // `RequireAuth` has already loaded it.
  const me = useMe().data;
  const guilds = useGuilds().data;
  const blocked = useBlocks().data;

  if (!me) return null;
  const discoverable = guilds?.filter((guild) => guild.discoverable).length;

  return (
    <div className="space-y-5">
      <div className="card flex items-center gap-3.5 p-4">
        <img src={me.avatarUrl} alt="" className="size-16 shrink-0 rounded-full" />
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-extrabold">{me.globalName ?? me.username}</h1>
          <p className="text-ink-muted truncate text-sm">@{me.username} · from Discord</p>
        </div>
      </div>
      <div className="grid gap-5 lg:grid-cols-2 lg:items-start">
        <div className="min-w-0 space-y-5">
          <Group title="Discovery">
            <LinkRow
              to="/onboarding"
              icon={Globe}
              title="Servers"
              sub={guilds && `Discoverable in ${discoverable} of ${guilds.length}`}
            />
            <LinkRow
              to="/people?tab=blocked"
              icon={Ban}
              title="Blocked people"
              sub={blocked && `${blocked.length} ${blocked.length === 1 ? 'person' : 'people'}`}
            />
          </Group>
          <Group title="Notifications">
            <PushRow />
            <InstallRow />
          </Group>
          <TimeZoneSetting />
          <AppearanceChoice />
        </div>
        <div className="min-w-0 space-y-5">
          <Account />
          <Group title="About">
            <li className="flex min-h-16 items-center gap-3 px-3.5 py-3">
              <RowLabel icon={Info} title="Con-Fuzion" sub="Free meetups for convention-goers." />
            </li>
          </Group>
        </div>
      </div>
    </div>
  );
}

/** The zone your profile keeps. Choosing one saves it. */
function TimeZoneSetting() {
  const zone = useViewerZone();
  const save = useSetTimeZone();
  return (
    <section aria-labelledby="time-heading" className="space-y-2">
      <GroupHeading id="time-heading">Time</GroupHeading>
      <div className="card p-3.5">
        <TimeZonePicker
          label="Your time zone"
          // The choice being saved, so the field doesn't flick back to the old one.
          value={save.isPending ? save.variables : zone}
          onChange={(next) => {
            if (next !== zone) save.mutate(next);
          }}
          hint="Meetups in another zone also show their times in yours."
          error={save.isError ? 'Couldn’t save your time zone. Try again.' : undefined}
        />
      </div>
    </section>
  );
}

function Account() {
  const navigate = useNavigate();
  const signOut = useSignOut(false);
  const everywhere = useSignOut(true);
  const leave = { onSuccess: () => void navigate('/welcome', { replace: true }) };
  const busy = signOut.isPending || everywhere.isPending;
  const error = signOut.error ?? everywhere.error;

  return (
    <div className="space-y-2">
      <Group title="Account">
        <ButtonRow
          icon={LogOut}
          title="Sign out"
          disabled={busy}
          onClick={() => signOut.mutate(undefined, leave)}
        />
        <ButtonRow
          icon={MonitorSmartphone}
          title="Sign out everywhere"
          sub="Ends your sessions on every device, including this one."
          disabled={busy}
          onClick={() => everywhere.mutate(undefined, leave)}
        />
      </Group>
      {error && <ErrorState error={error} />}
    </div>
  );
}
