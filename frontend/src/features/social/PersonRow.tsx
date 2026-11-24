import type { ReactNode } from 'react';
import type { Person } from '../../api/social';

/** One person: avatar (Discord CDN), display name as plain text, and their actions. */
export function PersonRow({
  person,
  children,
  below,
}: {
  person: Person;
  /** Action buttons. */
  children?: ReactNode;
  /** Shown under the row, such as an open confirmation. */
  below?: ReactNode;
}) {
  return (
    <li className="border-line bg-surface-raised rounded-card space-y-3 border p-3">
      <div className="flex items-center gap-3">
        <img src={person.avatarUrl} alt="" className="size-10 shrink-0 rounded-full" />
        <span className="min-w-0 flex-1 truncate font-medium">{person.displayName}</span>
        <div className="flex shrink-0 flex-wrap justify-end gap-2">{children}</div>
      </div>
      {below}
    </li>
  );
}
