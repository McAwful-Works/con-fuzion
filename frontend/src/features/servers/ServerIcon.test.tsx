import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ServerIcon } from './ServerIcon';

const icon = (url: string) => (
  <ServerIcon name="retro gamers" url={url} className="size-10" initialClassName="font-bold" />
);

describe('a server icon', () => {
  it('gives a new icon its own try after one failed', () => {
    const { container, rerender } = render(icon('/old.png'));
    fireEvent.error(container.querySelector('img')!);
    expect(screen.getByText('R')).toBeInTheDocument();

    rerender(icon('/new.png'));

    expect(container.querySelector('img')).toHaveAttribute('src', '/new.png');
    expect(screen.queryByText('R')).toBeNull();
  });
});
