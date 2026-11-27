import { useState } from 'react';

/**
 * A server's icon, or its initial while it has none or if the icon fails to load. It's decoration,
 * so screen readers skip both: the caller names the server beside it.
 */
export function ServerIcon({
  name,
  url,
  className,
  initialClassName,
}: {
  name: string;
  url: string | null;
  /** The size and shape, shared by the icon and the initial. */
  className: string;
  /** The initial's own colors and type. */
  initialClassName: string;
}) {
  // The icon that failed, so that a new one still gets its try.
  const [failed, setFailed] = useState<string>();

  if (url && url !== failed) {
    return <img src={url} alt="" className={className} onError={() => setFailed(url)} />;
  }
  return (
    <span
      aria-hidden="true"
      className={`${className} flex items-center justify-center ${initialClassName}`}
    >
      {/* By code point: half of an emoji doesn't draw. */}
      {Array.from(name)[0]?.toUpperCase()}
    </span>
  );
}
