import { render, screen } from '@testing-library/react';
import { RouterProvider } from 'react-router/dom';
import { createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from './router';

describe('app shell', () => {
  it('renders the wordmark, the home route, and the footer', () => {
    render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />);

    expect(screen.getByText('Con-Fuzion')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });
});
