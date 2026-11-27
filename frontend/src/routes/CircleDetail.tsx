import { Ban, ChevronLeft, Flag, UserMinus } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import {
  useCircle,
  useDeleteCircle,
  useRemoveCircleMember,
  useRenameCircle,
  type Circle,
  type Person,
} from '../api/social';
import { CardList } from '../components/CardList';
import { ConfirmPanel } from '../components/ConfirmPanel';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { CircleForm } from '../features/social/CircleForm';
import { CirclePeopleSearch } from '../features/social/PeopleSearch';
import { ReportSheet } from '../features/reports/ReportSheet';
import { BlockConfirm } from '../features/social/PersonActions';
import { PersonMenu } from '../features/social/PersonMenu';
import { PersonRow } from '../features/social/PersonRow';

function MemberRow({ circle, person }: { circle: Circle; person: Person }) {
  const remove = useRemoveCircleMember(circle.id);
  const [asking, setAsking] = useState<'block' | 'report' | null>(null);
  const done = () => setAsking(null);
  const name = person.displayName;
  return (
    <PersonRow
      person={person}
      below={
        <>
          {asking === 'block' && <BlockConfirm person={person} onDone={done} />}
          {asking === 'report' && (
            <ReportSheet
              targetType="USER"
              targetId={person.id}
              label={name}
              blockable={person}
              onDone={done}
            />
          )}
        </>
      }
    >
      <PersonMenu
        name={name}
        actions={[
          {
            text: 'Remove',
            label: `Remove ${name} from ${circle.name}`,
            icon: UserMinus,
            disabled: remove.isPending,
            onSelect: () => remove.mutate(person.id),
          },
          { text: 'Block', label: `Block ${name}`, icon: Ban, onSelect: () => setAsking('block') },
          {
            text: 'Report',
            label: `Report ${name}`,
            icon: Flag,
            onSelect: () => setAsking('report'),
          },
        ]}
      />
    </PersonRow>
  );
}

function DeleteCircle({ circle }: { circle: Circle }) {
  const navigate = useNavigate();
  const remove = useDeleteCircle(circle.id);
  const [asking, setAsking] = useState(false);

  if (!asking) {
    return (
      <button type="button" className="btn btn-danger btn-sm" onClick={() => setAsking(true)}>
        Delete circle
      </button>
    );
  }
  return (
    <ConfirmPanel
      title={`Delete “${circle.name}”?`}
      confirmLabel="Delete circle"
      pending={remove.isPending}
      error={remove.error}
      onCancel={() => setAsking(false)}
      onConfirm={() =>
        remove.mutate(undefined, { onSuccess: () => void navigate('/people?tab=circles') })
      }
    >
      <p>
        The circle is deleted. The people in it aren’t affected, and they aren’t told. This can’t be
        undone.
      </p>
    </ConfirmPanel>
  );
}

export function CircleDetail() {
  const { circleId = '' } = useParams();
  const circle = useCircle(circleId);
  const rename = useRenameCircle(circleId);
  const [renaming, setRenaming] = useState(false);

  if (circle.isPending) {
    return (
      <div role="status" aria-label="Loading the circle" className="space-y-3">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (
    circle.error instanceof ApiError &&
    (circle.error.status === 404 || circle.error.status === 400)
  ) {
    return (
      <div className="space-y-4">
        <h1 className="text-[1.75rem] leading-tight font-extrabold">Circle not found</h1>
        <Link to="/people?tab=circles" className="btn btn-secondary">
          Back to your circles
        </Link>
      </div>
    );
  }
  if (circle.isError)
    return <ErrorState error={circle.error} onRetry={() => void circle.refetch()} />;

  const data = circle.data;
  return (
    <div className="space-y-6">
      <div className="space-y-3">
        {/* Phones have Back in the top bar. */}
        <Link
          to="/people?tab=circles"
          className="text-ink-muted inline-flex min-h-11 items-center gap-1 text-sm font-bold max-lg:hidden"
        >
          <ChevronLeft aria-hidden="true" className="size-4" /> All circles
        </Link>
        <div>
          <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">Circle</p>
          <h1 className="text-[1.75rem] leading-tight font-extrabold break-words lg:text-[2.125rem]">
            {data.name}
          </h1>
        </div>
        {renaming ? (
          <div className="card space-y-3 p-4">
            <CircleForm
              label="Circle name"
              submitLabel="Save name"
              initialName={data.name}
              pending={rename.isPending}
              error={rename.error}
              onSubmit={(name) => rename.mutate(name, { onSuccess: () => setRenaming(false) })}
            />
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => setRenaming(false)}
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => setRenaming(true)}
          >
            Rename
          </button>
        )}
      </div>

      <section aria-labelledby="members-heading" className="space-y-3">
        <h2 id="members-heading" className="text-lg font-extrabold">
          Members
        </h2>
        {data.members.length === 0 ? (
          <p className="text-ink-muted">No one in this circle yet.</p>
        ) : (
          <CardList aria-label="Members">
            {data.members.map((person) => (
              <MemberRow key={person.id} circle={data} person={person} />
            ))}
          </CardList>
        )}
      </section>

      <section aria-labelledby="add-heading" className="space-y-3">
        <h2 id="add-heading" className="text-lg font-extrabold">
          Add people
        </h2>
        <CirclePeopleSearch circle={data} />
      </section>

      <section aria-label="Delete this circle">
        <DeleteCircle circle={data} />
      </section>
    </div>
  );
}
