import { ErrorState } from '../../components/ErrorState';

interface Props {
  query: {
    error: unknown;
    isFetchNextPageError: boolean;
    isFetchingNextPage: boolean;
    hasNextPage: boolean;
    fetchNextPage: () => unknown;
  };
}

/** Under a paged list: the next page, or a retry when loading it failed. */
export function LoadMore({ query }: Props) {
  const more = () => void query.fetchNextPage();
  return (
    <>
      {query.isFetchNextPageError && <ErrorState error={query.error} onRetry={more} />}
      {query.hasNextPage && (
        <button
          type="button"
          className="btn btn-secondary w-full sm:w-auto"
          disabled={query.isFetchingNextPage}
          onClick={more}
        >
          {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </>
  );
}
