/** CSS-only placeholder block for loading states. Size it with classes. */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div aria-hidden="true" className={`bg-line animate-pulse rounded-control ${className}`} />
  );
}
