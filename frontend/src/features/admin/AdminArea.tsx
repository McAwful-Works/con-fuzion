import { Outlet, Route, Routes } from 'react-router';
import { NotFound } from '../../components/NotFound';
import { ReportDetail } from './ReportDetail';
import { ReportQueue } from './ReportQueue';

function Layout() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Moderation</h1>
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
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
