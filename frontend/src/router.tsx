import type { RouteObject } from 'react-router';
import { AppShell } from './components/AppShell';
import { RequireAuth } from './features/auth/RequireAuth';
import { CircleDetail } from './routes/CircleDetail';
import { Circles } from './routes/Circles';
import { EventDetail } from './routes/EventDetail';
import { EventEdit } from './routes/EventEdit';
import { EventNew } from './routes/EventNew';
import { Friends } from './routes/Friends';
import { Home } from './routes/Home';
import { Invite } from './routes/Invite';
import { Landing } from './routes/Landing';
import { Onboarding } from './routes/Onboarding';
import { Schedule } from './routes/Schedule';
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
          { path: '/schedule', element: <Schedule /> },
          { path: '/events/new', element: <EventNew /> },
          { path: '/events/:eventId', element: <EventDetail /> },
          { path: '/events/:eventId/edit', element: <EventEdit /> },
          { path: '/friends', element: <Friends /> },
          { path: '/circles', element: <Circles /> },
          { path: '/circles/:circleId', element: <CircleDetail /> },
          { path: '/settings', element: <Settings /> },
        ],
      },
    ],
  },
];
