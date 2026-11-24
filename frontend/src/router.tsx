import type { RouteObject } from 'react-router';
import { AppShell } from './components/AppShell';
import { RequireAuth } from './features/auth/RequireAuth';
import { Home } from './routes/Home';
import { Landing } from './routes/Landing';
import { Onboarding } from './routes/Onboarding';
import { Settings } from './routes/Settings';

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { path: '/welcome', element: <Landing /> },
      {
        element: <RequireAuth />,
        children: [
          { index: true, element: <Home /> },
          { path: '/onboarding', element: <Onboarding /> },
          { path: '/settings', element: <Settings /> },
        ],
      },
    ],
  },
];
