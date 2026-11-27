import { useNavigate } from 'react-router';

/**
 * Goes to the page before this one, or replaces this one with `parent` when it's the first of the visit.
 * React Router keeps each history entry's position as `idx`. `location.key` can't tell, because a
 * redirect gives the first entry a new one.
 */
export function useGoBack(parent: string) {
  const navigate = useNavigate();
  return () => {
    const position = (window.history.state as { idx?: number } | null)?.idx ?? 0;
    void (position > 0 ? navigate(-1) : navigate(parent, { replace: true }));
  };
}
