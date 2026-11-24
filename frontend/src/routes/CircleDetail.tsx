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
import { ConfirmPanel } from '../components/ConfirmPanel';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { CircleForm } from '../features/social/CircleForm';
import { PeopleSearch } from '../features/social/PeopleSearch';
import { BlockConfirm } from '../features/social/PersonActions';
import { PersonRow } from '../features/social/PersonRow';

function MemberRow({ circle, person }: { circle: Circle; person: Person }) {
  const remove = useRemoveCircleMember(circle.id);
  const [blocking, setBlocking] = useState(false);
  return (
    <PersonRow
      person={person}
      below={blocking && <BlockConfirm person={person} onDone={() => setBlocking(false)} />}
    >
      <button
        type="button"
        className="btn btn-secondary"
        aria-label={`Remove ${person.displayName} from ${circle.name}`}
        disabled={remove.isPending}
        onClick={() => remove.mutate(person.id)}
      >
        Remove
      </button>
      <button
        type="button"
        className="btn btn-secondary"
        aria-label={`Block ${person.displayName}`}
        onClick={() => setBlocking(true)}
      >
        Block
      </button>
    </PersonRow>
  );
}

function DeleteCircle({ circle }: { circle: Circle }) {
  const navigate = useNavigate();
  const remove = useDeleteCircle(circle.id);
  const [asking, setAsking] = useState(false);

  if (!asking) {
    return (
      <button type="button" className="btn btn-secondary" onClick={() => setAsking(true)}>
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
      onConfirm={() => remove.mutate(undefined, { onSuccess: () => void navigate('/circles') })}
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
        <h1 className="text-2xl font-semibold">Circle not found</h1>
        <Link to="/circles" className="btn btn-secondary">
          Back to your circles
        </Link>
      </div>
    );
  }
  if (circle.isError)
    return <ErrorState error={circle.error} onRetry={() => void circle.refetch()} />;

  const data = circle.data;
  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <Link to="/circles" className="text-sm underline">
          All circles
        </Link>
        <h1 className="text-2xl font-semibold break-words">{data.name}</h1>
        {renaming ? (
          <CircleForm
            label="Circle name"
            submitLabel="Save name"
            initialName={data.name}
            pending={rename.isPending}
            error={rename.error}
            onSubmit={(name) => rename.mutate(name, { onSuccess: () => setRenaming(false) })}
          />
        ) : (
          <button type="button" className="btn btn-secondary" onClick={() => setRenaming(true)}>
            Rename
          </button>
        )}
      </div>

      <section aria-labelledby="members-heading" className="space-y-3">
        <h2 id="members-heading" className="text-xl font-semibold">
          Members
        </h2>
        {data.members.length === 0 ? (
          <p className="text-ink-muted">No one in this circle yet.</p>
        ) : (
          <ul className="space-y-2">
            {data.members.map((person) => (
              <MemberRow key={person.id} circle={data} person={person} />
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="add-heading" className="space-y-3">
        <h2 id="add-heading" className="text-xl font-semibold">
          Add people
        </h2>
        <PeopleSearch circle={data} />
      </section>

      <section aria-label="Delete this circle">
        <DeleteCircle circle={data} />
      </section>
    </div>
  );
}
