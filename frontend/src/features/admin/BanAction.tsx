import { useId, useState } from 'react';
import { REASON_MAX, useSetBan } from '../../api/admin';
import { ModAction } from './ModAction';

interface Props {
  discordId: string;
  /** Who it is, for the button: a name, or the ID itself. */
  name: string;
  banned: boolean;
  onStart: () => void;
  onDone: (message: string) => void;
}

/** Ban or unban, behind typing the Discord ID. */
function BanButton({ discordId, name, banned, onStart, onDone }: Props) {
  const setBan = useSetBan(discordId);
  const [typed, setTyped] = useState('');
  const id = useId();
  const verb = banned ? 'Unban' : 'Ban';

  return (
    <ModAction
      label={`${verb} ${name}`}
      confirm={verb}
      mutation={setBan}
      onOpen={() => {
        setTyped('');
        onStart();
      }}
      ready={typed.trim() === discordId}
      note={
        banned
          ? undefined
          : { label: 'Reason (the person never sees it)', max: REASON_MAX, required: true }
      }
      onSubmit={(reason, close) =>
        setBan.mutate(banned ? null : reason, {
          onSuccess: () => {
            close();
            onDone(`${name} is ${banned ? 'unbanned' : 'banned'}.`);
          },
        })
      }
    >
      <p className="text-sm">
        {banned
          ? 'This Discord account can sign in again. Nothing the ban did is undone.'
          : 'This Discord account can’t sign in. If it’s in use here, it’s signed out, its upcoming events are cancelled and its RSVPs end. Unbanning undoes none of that.'}
      </p>
      <div className="space-y-1">
        <label htmlFor={id} className="block text-sm font-medium break-all">
          Type the Discord ID {discordId} to confirm
        </label>
        <input
          id={id}
          value={typed}
          inputMode="numeric"
          autoComplete="off"
          required
          onChange={(e) => setTyped(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3"
        />
      </div>
    </ModAction>
  );
}

/**
 * Admin only (D-015 rule 4). One button that follows `banned`, so focus stays on it when the state
 * flips. When that isn't known, as for an ID with no account, both are offered.
 */
export function BanAction({
  discordId,
  name,
  banned,
}: Pick<Props, 'discordId' | 'name'> & { banned?: boolean }) {
  const [done, setDone] = useState('');
  const modes = banned === undefined ? [false, true] : [banned];

  return (
    <div className="space-y-3">
      {modes.map((mode, index) => (
        <BanButton
          // By position, so the one button survives `banned` changing.
          key={index}
          discordId={discordId}
          name={name}
          banned={mode}
          onStart={() => setDone('')}
          onDone={setDone}
        />
      ))}
      <p role="status" className="text-sm font-medium">
        {done}
      </p>
    </div>
  );
}
