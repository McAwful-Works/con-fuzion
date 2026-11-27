/*
 * Cover photos by tag (D-017). They're licensed photos that ship with the app: hosts can't upload
 * one. Until a photo is there, its cover is a striped CSS placeholder.
 *
 * To add a photo, put it in src/assets/covers/ under the `file` name below (or change that name,
 * and nothing else) and give it a row in ASSETS.md. An 800px wide copy beside it, named like
 * `cover-food-800.jpg`, is what small screens download.
 */
import { coverFiles } from './coverFiles';

interface Cover {
  /** The preset tag. The default cover has none. */
  tag?: string;
  /** Landscape, at least 1600×900. */
  file: string;
  /** The utility in index.css that colors its stripes. */
  placeholder: string;
}

export const DEFAULT_COVER: Cover = {
  file: 'cover-default.jpg',
  placeholder: 'placeholder-cover-default',
};

const TAG_COVERS: Cover[] = [
  { tag: 'Tabletop', file: 'cover-tabletop.jpg', placeholder: 'placeholder-cover-tabletop' },
  { tag: 'Photoshoot', file: 'cover-photoshoot.jpg', placeholder: 'placeholder-cover-photoshoot' },
  { tag: 'Food', file: 'cover-food.jpg', placeholder: 'placeholder-cover-food' },
  { tag: 'Music', file: 'cover-music.jpg', placeholder: 'placeholder-cover-music' },
  { tag: 'Panel', file: 'cover-panel.jpg', placeholder: 'placeholder-cover-panel' },
  { tag: 'Shopping', file: 'cover-shopping.jpg', placeholder: 'placeholder-cover-shopping' },
  { tag: 'Hobby', file: 'cover-hobby.jpg', placeholder: 'placeholder-cover-hobby' },
];

/** The preset's cover, whatever its case. A host's own tag, or none, gets the default. */
export function coverFor(tag: string | null): Cover {
  const wanted = tag?.trim().toLowerCase();
  return TAG_COVERS.find((cover) => cover.tag?.toLowerCase() === wanted) ?? DEFAULT_COVER;
}

/** What an `<img>` needs to show the photo, or nothing while it isn't in the app. */
export function coverPhoto(file: string) {
  const full = coverFiles[file];
  if (!full) return undefined;
  const small = coverFiles[file.replace(/\.\w+$/, '-800$&')];
  return { src: full, srcSet: `${small ? `${small} 800w, ` : ''}${full} 1600w` };
}
