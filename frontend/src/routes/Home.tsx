import { Link } from 'react-router';

export function Home() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Meetups are coming soon</h1>
      <div className="flex flex-col gap-3 sm:flex-row">
        <Link to="/friends" className="btn btn-secondary">
          Friends
        </Link>
        <Link to="/circles" className="btn btn-secondary">
          Circles
        </Link>
        <Link to="/onboarding" className="btn btn-secondary">
          Choose where you’re discoverable
        </Link>
      </div>
    </div>
  );
}
