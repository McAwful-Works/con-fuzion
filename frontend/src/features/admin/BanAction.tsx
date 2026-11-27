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
  // What was typed counts only for the action it was typed under: a Ban form that turns into Unban,
  // because someone else banned them meanwhile, has to be confirmed again.
  const [entry, setEntry] = useState({ banned, text: '' });
  const typed = entry.banned === banned ? entry.text : '';
  const id = useId();
  const verb = banned ? 'Unban' : 'Ban';

  return (
    <ModAction
      label={`${verb} ${name}`}
      confirm={verb}
      mutation={setBan}
      onOpen={() => {
        setEntry({ banned, text: '' });
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
      <div className="field">
        <label htmlFor={id} className="field-label break-words">
          Type the Discord ID {discordId} to confirm
        </label>
        <input
          id={id}
          value={typed}
          inputMode="numeric"
          autoComplete="off"
          required
          onChange={(e) => setEntry({ banned, text: e.target.value })}
          className="field-control"
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
