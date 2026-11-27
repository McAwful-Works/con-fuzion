import { ChevronRight, type LucideIcon } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { Link } from 'react-router';
import { CardList } from '../../components/CardList';

const ROW = 'rounded-card flex min-h-16 w-full items-center gap-3 px-3.5 py-3 text-left';

/** The uppercase heading over a group on You. */
export function GroupHeading({ id, children }: { id: string; children: ReactNode }) {
  return (
    <h2
      id={id}
      className="text-ink-muted px-0.5 font-sans text-[0.8125rem] font-extrabold tracking-wider uppercase"
    >
      {children}
    </h2>
  );
}

/** A titled group of rows. */
export function Group({ title, children }: { title: string; children: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="space-y-2">
      <GroupHeading id={id}>{title}</GroupHeading>
      <CardList>{children}</CardList>
    </section>
  );
}

interface Label {
  icon: LucideIcon;
  title: string;
  /** The line under the title. */
  sub?: string;
}

/** A row's icon tile, title and the line under it. */
export function RowLabel({
  icon: Icon,
  title,
  sub,
  titleId,
  subId,
}: Label & { titleId?: string; subId?: string }) {
  return (
    <>
      <span className="rounded-control bg-surface-sunken flex size-10 shrink-0 items-center justify-center">
        <Icon aria-hidden="true" className="size-5" />
      </span>
      <span className="min-w-0 flex-1">
        <span id={titleId} className="block font-bold">
          {title}
        </span>
        {sub && (
          <span id={subId} className="text-ink-muted block text-[0.8125rem] font-normal">
            {sub}
          </span>
        )}
      </span>
    </>
  );
}

export function LinkRow({ to, ...label }: Label & { to: string }) {
  return (
    <li>
      <Link to={to} className={ROW}>
        <RowLabel {...label} />
        <ChevronRight aria-hidden="true" className="size-4 shrink-0" />
      </Link>
    </li>
  );
}

/** A row that is one button. The title alone is its name; the line under it describes it. */
export function ButtonRow({
  disabled,
  onClick,
  ...label
}: Label & { disabled: boolean; onClick: () => void }) {
  const id = useId();
  const titleId = `${id}-title`;
  const subId = `${id}-sub`;
  return (
    <li>
      <button
        type="button"
        aria-labelledby={titleId}
        aria-describedby={label.sub ? subId : undefined}
        disabled={disabled}
        onClick={onClick}
        className={`${ROW} disabled:opacity-50`}
      >
        <RowLabel {...label} titleId={titleId} subId={subId} />
      </button>
    </li>
  );
}
