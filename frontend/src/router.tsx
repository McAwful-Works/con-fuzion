import { Navigate, type RouteObject } from 'react-router';
import { AppShell } from './components/AppShell';
import { NotFound } from './components/NotFound';
import type { ShellPage } from './components/shell/page';
import { AdminError, AdminRoute } from './features/admin/AdminRoute';
import { RequireAuth } from './features/auth/RequireAuth';
import { CircleDetail } from './routes/CircleDetail';
import { EventDetail } from './routes/EventDetail';
import { EventEdit } from './routes/EventEdit';
import { EventNew } from './routes/EventNew';
import { Home } from './routes/Home';
import { Invite } from './routes/Invite';
import { Landing } from './routes/Landing';
import { Notifications } from './routes/Notifications';
import { Onboarding } from './routes/Onboarding';
import { People } from './routes/People';
import { Schedule } from './routes/Schedule';
import { Settings } from './routes/Settings';

const home = () => '/';

/** Paths from before the five places (D-017). Old notifications and bookmarks still use them. */
const MOVED: RouteObject[] = Object.entries({
  '/schedule': '/plans',
  '/friends': '/people',
  '/circles': '/people?tab=circles',
  '/notifications': '/inbox',
  '/settings': '/you',
}).map(([path, to]) => ({ path, element: <Navigate to={to} replace /> }));

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { path: '/welcome', element: <Landing /> },
      // Outside RequireAuth: a signed-out visitor is sent to sign in and brought back here.
      { path: '/invite/:token', element: <Invite /> },
      ...MOVED,
      {
        element: <RequireAuth />,
        children: [
          { index: true, element: <Home /> },
          { path: '/onboarding', element: <Onboarding /> },
          { path: '/plans', element: <Schedule /> },
          { path: '/people', element: <People /> },
          { path: '/inbox', element: <Notifications /> },
          {
            path: '/you',
            element: <Settings />,
            handle: { title: 'You', parent: home } satisfies ShellPage,
          },
          {
            path: '/events/new',
            element: <EventNew />,
            handle: { title: 'Host a meetup', parent: home, detail: true } satisfies ShellPage,
          },
          {
            path: '/events/:eventId',
            element: <EventDetail />,
            handle: { title: 'Meetup', parent: home, detail: true } satisfies ShellPage,
          },
          {
            path: '/events/:eventId/edit',
            element: <EventEdit />,
            handle: {
              title: 'Edit meetup',
              parent: ({ eventId }) => `/events/${eventId}`,
              detail: true,
            } satisfies ShellPage,
          },
          {
            path: '/circles/:circleId',
            element: <CircleDetail />,
            handle: {
              title: 'Circle',
              parent: () => '/people?tab=circles',
              detail: true,
            } satisfies ShellPage,
          },
          {
            path: '/admin/*',
            element: <AdminRoute />,
            errorElement: <AdminError />,
            handle: { title: 'Moderation', parent: home, moderatorsOnly: true } satisfies ShellPage,
          },
        ],
      },
      { path: '*', element: <NotFound /> },
    ],
  },
];
