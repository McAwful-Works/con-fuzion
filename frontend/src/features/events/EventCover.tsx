import { useState } from 'react';
import { coverFor, coverPhoto } from './covers';

/**
 * The tag's cover: its photo, or the placeholder while there is none or if it fails to load. It's
 * decoration, so screen readers skip both. The caller sizes it and places the placeholder's label.
 */
export function EventCover({
  tag,
  sizes,
  className,
}: {
  tag: string | null;
  /** How wide the photo is drawn, as in `<img sizes>`. */
  sizes: string;
  className: string;
}) {
  const cover = coverFor(tag);
  const photo = coverPhoto(cover.file);
  // The photo that failed, so that another tag's photo still gets its try.
  const [failed, setFailed] = useState<string>();

  return (
    <div className={`placeholder-cover ${cover.placeholder} flex ${className}`}>
      {photo && photo.src !== failed ? (
        <img
          alt=""
          loading="lazy"
          decoding="async"
          sizes={sizes}
          srcSet={photo.srcSet}
          src={photo.src}
          onError={() => setFailed(photo.src)}
          // Out of the flow, so the photo's own size never sets the cover's.
          className="absolute inset-0 size-full object-cover"
        />
      ) : (
        <span aria-hidden="true" className="cover-label">
          Licensed photo{cover.tag && ` · ${cover.tag}`}
        </span>
      )}
    </div>
  );
}
