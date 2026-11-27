import type { ComponentProps } from 'react';

/** A list as one card, with dashed lines between its rows. */
export function CardList({ className = '', ...props }: ComponentProps<'ul'>) {
  return <ul {...props} className={`card divide-line divide-y divide-dashed ${className}`} />;
}
