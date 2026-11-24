import { useEffect, useId, useRef, type ReactNode } from 'react';
import { ErrorState } from './ErrorState';

interface Props {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  pending?: boolean;
  error?: unknown;
}

/**
 * An inline confirmation for a consequential action. Focus lands on the safe button, Escape
 * cancels, and the text says what will happen before anything does.
 */
export function ConfirmPanel({
  title,
  children,
  confirmLabel,
  onConfirm,
  onCancel,
  pending = false,
  error,
}: Props) {
  const titleId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    // Focus the safe button, and give focus back to whatever opened the panel when it closes.
    const opener = document.activeElement;
    cancelRef.current?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);

  return (
    <div
      role="alertdialog"
      aria-labelledby={titleId}
      onKeyDown={(e) => e.key === 'Escape' && !pending && onCancel()}
      className="border-line bg-surface-raised rounded-card space-y-3 border p-4"
    >
      <h3 id={titleId} className="font-semibold">
        {title}
      </h3>
      <div className="space-y-2 text-sm">{children}</div>
      {error !== undefined && error !== null && <ErrorState error={error} />}
      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          ref={cancelRef}
          className="btn btn-secondary"
          disabled={pending}
          onClick={onCancel}
        >
          Cancel
        </button>
        <button type="button" className="btn btn-primary" disabled={pending} onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </div>
  );
}
