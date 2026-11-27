import type { ReactNode } from 'react';
import type { Person } from '../../api/social';

/**
 * One person in a `CardList`: avatar (Discord CDN), display name as plain text, and their actions.
 */
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
    <li className="space-y-3 px-3.5 py-3">
      <div className="flex flex-wrap items-center gap-3">
        <img src={person.avatarUrl} alt="" className="size-11 shrink-0 rounded-full" />
        <span className="min-w-0 flex-1 basis-28 truncate font-bold">{person.displayName}</span>
        <div className="flex flex-wrap gap-2">{children}</div>
      </div>
      {below}
    </li>
  );
}
