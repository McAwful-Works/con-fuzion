import { useMe } from '../../api/me';
import { browserTimeZone, isValidTimeZone } from './time';

/** The viewer's own time zone: the profile's, or the browser's until the profile has one. */
export function useViewerZone(): string {
  const zone = useMe().data?.timeZone;
  return zone && isValidTimeZone(zone) ? zone : browserTimeZone();
}
