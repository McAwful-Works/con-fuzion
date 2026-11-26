import { NavLink, Outlet, Route, Routes } from 'react-router';
import { isAdmin, useMe } from '../../api/me';
import { NotFound } from '../../components/NotFound';
import { AuditLog } from './AuditLog';
import { ReportDetail } from './ReportDetail';
import { ReportQueue } from './ReportQueue';
import { UserSearch } from './UserSearch';

const SECTIONS = [
  { to: '/admin', label: 'Reports' },
  { to: '/admin/users', label: 'Users' },
];
const ADMIN_SECTIONS = [...SECTIONS, { to: '/admin/audit', label: 'Audit log' }];

function Layout() {
  const sections = isAdmin(useMe().data) ? ADMIN_SECTIONS : SECTIONS;
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Moderation</h1>
      <nav aria-label="Moderation sections" className="flex flex-wrap gap-2">
        {sections.map((section) => (
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
  const admin = isAdmin(useMe().data);
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<ReportQueue />} />
        <Route path="reports/:reportId" element={<ReportDetail />} />
        <Route path="users" element={<UserSearch />} />
        {/* For anyone else there's no such page, and nothing is asked of the server. */}
        {admin && <Route path="audit" element={<AuditLog />} />}
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
