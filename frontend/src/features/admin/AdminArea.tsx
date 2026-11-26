import { NavLink, Outlet, Route, Routes } from 'react-router';
import { NotFound } from '../../components/NotFound';
import { ReportDetail } from './ReportDetail';
import { ReportQueue } from './ReportQueue';
import { UserSearch } from './UserSearch';

const SECTIONS = [
  { to: '/admin', label: 'Reports' },
  { to: '/admin/users', label: 'Users' },
];

function Layout() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Moderation</h1>
      <nav aria-label="Moderation sections" className="flex flex-wrap gap-2">
        {SECTIONS.map((section) => (
          <NavLink
            key={section.to}
            to={section.to}
            end
            className={({ isActive }) => `btn ${isActive ? 'btn-primary' : 'btn-secondary'}`}
          >
            {section.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  );
}

/** Everything under `/admin`, loaded only through `AdminRoute`. */
export default function AdminArea() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<ReportQueue />} />
        <Route path="reports/:reportId" element={<ReportDetail />} />
        <Route path="users" element={<UserSearch />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
