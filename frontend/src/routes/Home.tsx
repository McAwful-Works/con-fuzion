import { Link } from 'react-router';

export function Home() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Meetups are coming soon</h1>
      <Link to="/onboarding" className="btn btn-secondary">
        Choose where you’re discoverable
      </Link>
    </div>
  );
}
