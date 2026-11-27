import { Link, Outlet, Route, Routes, useMatch } from 'react-router';
import { isAdmin, useMe } from '../../api/me';
import { NotFound } from '../../components/NotFound';
import { AuditLog } from './AuditLog';
import { ReportDetail } from './ReportDetail';
import { NoReportSelected, Reports } from './Reports';
import { UserSearch } from './UserSearch';

const SECTIONS = [
  { to: '/admin', label: 'Reports' },
  { to: '/admin/users', label: 'Users' },
];
const ADMIN_SECTIONS = [...SECTIONS, { to: '/admin/audit', label: 'Audit log' }];

function SectionLink({ to, label }: (typeof SECTIONS)[number]) {
  const here = useMatch(to) !== null;
  // A single report is still part of Reports.
  const onReport = useMatch('/admin/reports/:reportId') !== null && to === '/admin';
  return (
    <Link to={to} aria-current={here ? 'page' : onReport ? 'true' : undefined} className="seg-item">
      {label}
    </Link>
  );
}

function Layout() {
  const sections = isAdmin(useMe().data) ? ADMIN_SECTIONS : SECTIONS;
  return (
    // The `main:has(>&)` class widens the shell's column on desktop, for the tables. It works only
    // while this element is a direct child of `<main>`.
    <div className="space-y-5 [main:has(>&)]:lg:max-w-[77.5rem]">
      <div>
        <p className="text-ink-muted text-xs font-bold tracking-wider uppercase">
          Moderators and admins
        </p>
        <h1 className="text-3xl font-extrabold">Moderation</h1>
      </div>
      <nav aria-label="Moderation sections" className="seg">
        {sections.map((section) => (
          <SectionLink key={section.to} {...section} />
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
        <Route element={<Reports />}>
          <Route index element={<NoReportSelected />} />
          <Route path="reports/:reportId" element={<ReportDetail />} />
        </Route>
        <Route path="users" element={<UserSearch />} />
        {/* For anyone else there's no such page, and nothing is asked of the server. */}
        {admin && <Route path="audit" element={<AuditLog />} />}
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
