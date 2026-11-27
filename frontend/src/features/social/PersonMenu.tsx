import { Ellipsis, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

export interface MenuAction {
  text: string;
  /** The accessible name: `text`, then who it acts on. */
  label: string;
  icon: LucideIcon;
  onSelect: () => void;
  disabled?: boolean;
}

/**
 * The "…" menu on a person's row. A plain disclosure of buttons: Escape, a press elsewhere and
 * tabbing out close it.
 */
export function PersonMenu({ name, actions }: { name: string; actions: MenuAction[] }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    // On the last row it can open under the tab bar; the page's scroll padding clears that.
    panel.current?.scrollIntoView?.({ block: 'nearest' });
    const onPress = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      toggle.current?.focus();
    };
    document.addEventListener('pointerdown', onPress);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPress);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div
      ref={root}
      className="relative"
      onBlur={(e) => {
        // Safari doesn't focus a pressed button, so only a move to something else counts.
        if (e.relatedTarget && !e.currentTarget.contains(e.relatedTarget)) setOpen(false);
      }}
    >
      <button
        ref={toggle}
        type="button"
        aria-label={`More actions for ${name}`}
        aria-expanded={open}
        className="flex size-11 items-center justify-center rounded-full"
        onClick={() => setOpen(!open)}
      >
        <Ellipsis aria-hidden="true" className="size-5" />
      </button>
      {open && (
        <div
          ref={panel}
          className="card absolute top-full right-0 z-20 mt-1 flex w-44 flex-col p-1"
        >
          {actions.map(({ text, label, icon: Icon, onSelect, disabled }) => (
            <button
              key={text}
              type="button"
              aria-label={label}
              disabled={disabled}
              className="rounded-control hover:bg-surface-sunken flex min-h-11 items-center gap-2.5 px-3 text-sm font-semibold disabled:opacity-50"
              onClick={() => {
                // So that whatever this opens can hand focus back to the "…" button.
                toggle.current?.focus();
                setOpen(false);
                onSelect();
              }}
            >
              <Icon aria-hidden="true" className="size-4 shrink-0" />
              {text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
