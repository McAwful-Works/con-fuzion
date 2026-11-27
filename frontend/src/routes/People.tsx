import { useSearchParams } from 'react-router';
import { BlockedList, BlockedNote } from '../features/social/BlockedList';
import { Circles } from './Circles';
import { Friends } from './Friends';

/** `/people`: your friends, or with `?tab=` your circles or the people you've blocked. */
export function People() {
  const [params] = useSearchParams();
  const tab = params.get('tab');
  if (tab === 'circles') return <Circles />;
  if (tab !== 'blocked') return <Friends />;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Blocked</h1>
      <BlockedNote />
      <BlockedList />
    </div>
  );
}
