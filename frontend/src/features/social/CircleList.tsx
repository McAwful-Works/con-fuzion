import { ChevronRight, UsersRound } from 'lucide-react';
import { Link } from 'react-router';
import { useCircles } from '../../api/social';
import { CardList } from '../../components/CardList';
import { ErrorState } from '../../components/ErrorState';
import { Skeleton } from '../../components/Skeleton';

/** The tile colors, used in turn down the list. */
const TILES = [
  'bg-going text-going-ink',
  'bg-waiting text-waiting-ink',
  'bg-hosting text-hosting-ink',
];

/** Your circles, each linking to its page. */
export function CircleList() {
  const circles = useCircles();

  if (circles.isPending) {
    return (
      <div role="status" aria-label="Loading your circles" className="space-y-3">
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }
  if (circles.isError)
    return <ErrorState error={circles.error} onRetry={() => void circles.refetch()} />;
  if (circles.data.length === 0) return <p className="text-ink-muted">No circles yet.</p>;

  return (
    <CardList aria-label="Your circles">
      {circles.data.map((circle, i) => (
        <li key={circle.id}>
          <Link
            to={`/circles/${circle.id}`}
            className="rounded-card flex min-h-16 items-center gap-3 px-3.5 py-3"
          >
            <span
              className={`rounded-control flex size-10 shrink-0 items-center justify-center ${TILES[i % TILES.length]}`}
            >
              <UsersRound aria-hidden="true" className="size-5" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-bold">{circle.name}</span>{' '}
              <span className="text-ink-muted block text-[0.8125rem]">
                {circle.members.length} {circle.members.length === 1 ? 'person' : 'people'}
              </span>
            </span>
            <ChevronRight aria-hidden="true" className="size-4 shrink-0" />
          </Link>
        </li>
      ))}
    </CardList>
  );
}
