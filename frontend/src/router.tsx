import type { RouteObject } from 'react-router';
import { AppShell } from './components/AppShell';
import { Home } from './routes/Home';

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [{ index: true, element: <Home /> }],
  },
];
