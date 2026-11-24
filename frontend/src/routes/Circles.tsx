import { useState } from 'react';
import { Link } from 'react-router';
import { useCircles, useCreateCircle } from '../api/social';
import { ErrorState } from '../components/ErrorState';
import { Skeleton } from '../components/Skeleton';
import { CircleForm } from '../features/social/CircleForm';

export function Circles() {
  const circles = useCircles();
  const create = useCreateCircle();
  // Clears the field after a success only: a failed create keeps what was typed.
  const [formKey, setFormKey] = useState(0);

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Circles</h1>
      <p className="text-ink-muted text-sm">
        A circle is a group of people you can invite to meetups in one go. Circles are private to
        you. The people in them aren’t told.
      </p>

      <section aria-labelledby="new-circle-heading" className="space-y-3">
        <h2 id="new-circle-heading" className="text-xl font-semibold">
          New circle
        </h2>
        <CircleForm
          key={formKey}
          label="Circle name"
          submitLabel="Create circle"
          pending={create.isPending}
          error={create.error}
          onSubmit={(name) => create.mutate(name, { onSuccess: () => setFormKey((k) => k + 1) })}
        />
      </section>

      <section aria-labelledby="circles-heading" className="space-y-3">
        <h2 id="circles-heading" className="text-xl font-semibold">
          Your circles
        </h2>
        {circles.isPending && (
          <div role="status" aria-label="Loading your circles" className="space-y-3">
            <Skeleton className="h-14 w-full" />
          </div>
        )}
        {circles.isError && (
          <ErrorState error={circles.error} onRetry={() => void circles.refetch()} />
        )}
        {circles.data && circles.data.length === 0 && (
          <p className="text-ink-muted">No circles yet.</p>
        )}
        {circles.data && circles.data.length > 0 && (
          <ul className="space-y-2">
            {circles.data.map((circle) => (
              <li key={circle.id}>
                <Link
                  to={`/circles/${circle.id}`}
                  className="border-line bg-surface-raised rounded-card flex min-h-14 items-center justify-between gap-3 border px-4"
                >
                  <span className="truncate font-medium">{circle.name}</span>
                  <span className="text-ink-muted shrink-0 text-sm">
                    {circle.members.length} {circle.members.length === 1 ? 'person' : 'people'}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
