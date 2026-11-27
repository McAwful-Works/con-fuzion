import { useState } from 'react';
import { useCreateCircle } from '../api/social';
import { CircleForm } from '../features/social/CircleForm';
import { CircleList } from '../features/social/CircleList';

/** The Circles tab of People. */
export function Circles() {
  const create = useCreateCircle();
  // Clears the field after a success only: a failed create keeps what was typed.
  const [formKey, setFormKey] = useState(0);

  return (
    <div className="space-y-4">
      <p className="text-ink-muted text-sm">
        A circle is a group of people you can invite to meetups in one go. Circles are private to
        you. The people in them aren’t told.
      </p>

      <CircleList />

      <section aria-labelledby="new-circle-heading" className="card space-y-3 p-4">
        <h2 id="new-circle-heading" className="text-lg font-extrabold">
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
    </div>
  );
}
