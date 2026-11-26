import { useState } from 'react';
import { useSetRoles, useUserRoles, type GrantedRole } from '../../api/admin';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';
import { words } from './labels';
import { ModAction } from './ModAction';

const ROLES: GrantedRole[] = ['MODERATOR', 'ADMIN'];

interface FieldsProps {
  userId: string;
  /** The new set, or null while it's unchanged. */
  picked: GrantedRole[] | null;
  onPick: (roles: GrantedRole[]) => void;
}

/** Mounted with the form, so the roles are only asked for when someone wants to see them. */
function RoleFields({ userId, picked, onPick }: FieldsProps) {
  const roles = useUserRoles(userId);

  if (roles.isPending) {
    return (
      <div role="status" aria-label="Loading roles">
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (!roles.data) {
    return <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />;
  }
  const current = picked ?? roles.data.roles;

  return (
    <>
      <fieldset>
        <legend className="text-sm font-medium">Roles</legend>
        {ROLES.map((role) => (
          <label key={role} className="flex min-h-11 items-center gap-3">
            <input
              type="checkbox"
              checked={current.includes(role)}
              onChange={(e) =>
                onPick(ROLES.filter((r) => (r === role ? e.target.checked : current.includes(r))))
              }
              className="size-5 shrink-0"
            />
            <span>{words(role)}</span>
          </label>
        ))}
      </fieldset>
      {!roles.data.mfaEnabled && (
        <p className="text-sm">
          Discord two-factor authentication (2FA) was off at their last sign-in, so a role doesn’t
          take effect until they turn it on and sign in again.
        </p>
      )}
    </>
  );
}

/** Admin only: the moderator and admin roles one person holds. */
export function RolesAction({ userId, name }: { userId: string; name: string }) {
  const setRoles = useSetRoles(userId);
  const [picked, setPicked] = useState<GrantedRole[] | null>(null);
  const [done, setDone] = useState('');

  return (
    <div className="space-y-3">
      <ModAction
        label={`Roles for ${name}`}
        confirm="Save roles"
        mutation={setRoles}
        onOpen={() => {
          setPicked(null);
          setDone('');
        }}
        ready={picked !== null}
        onSubmit={(_, close) =>
          setRoles.mutate(picked!, {
            onSuccess: () => {
              close();
              setDone(`Roles for ${name} saved.`);
            },
          })
        }
      >
        <p className="text-sm">
          A change signs them out, and that includes you if this is your own account.
        </p>
        <RoleFields userId={userId} picked={picked} onPick={setPicked} />
      </ModAction>
      <p role="status" className="text-sm font-medium">
        {done}
      </p>
    </div>
  );
}
