import { ConfirmPanel } from '../../components/ConfirmPanel';
import { useBlock, useRemoveFriend, type Person } from '../../api/social';

/** Confirmations for the two ways to end things with someone. Each row renders the one it opened. */
export function BlockConfirm({ person, onDone }: { person: Person; onDone: () => void }) {
  const block = useBlock();
  return (
    <ConfirmPanel
      title={`Block ${person.displayName}?`}
      confirmLabel="Block"
      pending={block.isPending}
      error={block.error}
      onCancel={onDone}
      onConfirm={() => block.mutate(person.id, { onSuccess: onDone })}
    >
      <p>Blocking cuts you off from each other, both ways:</p>
      <ul className="list-disc space-y-1 pl-5">
        <li>You stop being friends, if you were.</li>
        <li>You’re each taken out of the other’s circles.</li>
        <li>Neither of you can find or pick the other, or use the other’s invite links.</li>
        <li>Neither of you can see the other’s events.</li>
      </ul>
      <p>
        They aren’t told. If you unblock them later, none of this is put back: you’d have to become
        friends and rebuild your circles again.
      </p>
    </ConfirmPanel>
  );
}

export function RemoveFriendConfirm({ person, onDone }: { person: Person; onDone: () => void }) {
  const remove = useRemoveFriend();
  return (
    <ConfirmPanel
      title={`Remove ${person.displayName} as a friend?`}
      confirmLabel="Remove friend"
      pending={remove.isPending}
      error={remove.error}
      onCancel={onDone}
      onConfirm={() => remove.mutate(person.id, { onSuccess: onDone })}
    >
      <p>
        This ends the friendship for both of you. It also takes each of you out of the other’s
        circles. You can become friends again with a new invite link.
      </p>
    </ConfirmPanel>
  );
}
