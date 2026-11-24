import type { RouteObject } from 'react-router';
import { AppShell } from './components/AppShell';
import { RequireAuth } from './features/auth/RequireAuth';
import { CircleDetail } from './routes/CircleDetail';
import { Circles } from './routes/Circles';
import { Friends } from './routes/Friends';
import { Home } from './routes/Home';
import { Invite } from './routes/Invite';
import { Landing } from './routes/Landing';
import { Onboarding } from './routes/Onboarding';
import { Settings } from './routes/Settings';

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { path: '/welcome', element: <Landing /> },
      // Outside RequireAuth: a signed-out visitor is sent to sign in and brought back here.
      { path: '/invite/:token', element: <Invite /> },
      {
        element: <RequireAuth />,
        children: [
          { index: true, element: <Home /> },
          { path: '/onboarding', element: <Onboarding /> },
          { path: '/friends', element: <Friends /> },
          { path: '/circles', element: <Circles /> },
          { path: '/circles/:circleId', element: <CircleDetail /> },
          { path: '/settings', element: <Settings /> },
        ],
      },
    ],
  },
];
