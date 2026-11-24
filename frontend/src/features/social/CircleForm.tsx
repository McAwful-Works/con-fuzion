import { useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { CIRCLE_NAME_MAX } from '../../api/social';

interface Props {
  label: string;
  submitLabel: string;
  initialName?: string;
  pending: boolean;
  error: unknown;
  onSubmit: (name: string) => void;
}

/** A circle name field (1 to 50 characters) with its own 409 message. Used to create and rename. */
export function CircleForm({
  label,
  submitLabel,
  initialName = '',
  pending,
  error,
  onSubmit,
}: Props) {
  const [name, setName] = useState(initialName);
  const trimmed = name.trim();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (trimmed) onSubmit(trimmed);
  };

  return (
    <form onSubmit={submit} className="space-y-2">
      <label className="block space-y-1">
        <span className="text-sm font-medium">{label}</span>
        <input
          type="text"
          value={name}
          maxLength={CIRCLE_NAME_MAX}
          onChange={(e) => setName(e.target.value)}
          className="border-line bg-surface-raised rounded-control min-h-11 w-full border px-3"
        />
      </label>
      <button type="submit" className="btn btn-primary" disabled={pending || !trimmed}>
        {submitLabel}
      </button>
      {error !== null && error !== undefined && (
        <p role="alert" className="text-sm">
          {error instanceof ApiError && error.status === 409
            ? 'You already have a circle with that name.'
            : error instanceof ApiError && error.status === 400
              ? 'That name isn’t allowed. Use 1 to 50 characters.'
              : 'Something went wrong. Try again.'}
        </p>
      )}
    </form>
  );
}
