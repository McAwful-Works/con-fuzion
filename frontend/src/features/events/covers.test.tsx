import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { coverFor, coverPhoto, DEFAULT_COVER } from './covers';
import { PRESET_TAGS } from './DetailsFields';
import { EventCover } from './EventCover';

// Stands in for src/assets/covers/, whatever the app really holds.
const files = vi.hoisted(() => ({}) as Record<string, string>);
vi.mock('./coverFiles', () => ({ coverFiles: files }));

beforeEach(() => {
  for (const name of Object.keys(files)) delete files[name];
});

/** Puts the tag's photo in the app, with or without its small copy. */
function addPhoto(tag: string, { small = true } = {}) {
  const { file } = coverFor(tag);
  files[file] = `/assets/${tag}.jpg`;
  if (small) files[file.replace('.', '-800.')] = `/assets/${tag}-800.jpg`;
}

const show = (tag: string | null) =>
  render(<EventCover tag={tag} sizes="100vw" className="relative" />);

describe('the tag → cover map', () => {
  it('gives each preset tag a cover of its own', () => {
    const covers = PRESET_TAGS.map((tag) => coverFor(tag));
    expect(covers.map((cover) => cover.tag)).toEqual(PRESET_TAGS);
    for (const key of ['file', 'placeholder'] as const) {
      const all = [...covers, DEFAULT_COVER].map((cover) => cover[key]);
      expect(new Set(all).size).toBe(PRESET_TAGS.length + 1);
    }
  });

  it('matches a preset whatever its case', () => {
    expect(coverFor('tabletop')).toBe(coverFor('Tabletop'));
    expect(coverFor(' FOOD ')).toBe(coverFor('Food'));
  });

  it.each([null, '', 'Other', 'Karaoke', 'Tabletop games', 'constructor'])(
    'gives %j the default',
    (tag) => expect(coverFor(tag)).toBe(DEFAULT_COVER),
  );

  it('offers a photo’s 800px copy to small screens, when there is one', () => {
    const { file } = coverFor('Music');
    expect(coverPhoto(file)).toBeUndefined();
    addPhoto('Music', { small: false });
    expect(coverPhoto(file)).toEqual({
      src: '/assets/Music.jpg',
      srcSet: '/assets/Music.jpg 1600w',
    });
    addPhoto('Music');
    expect(coverPhoto(file)?.srcSet).toBe('/assets/Music-800.jpg 800w, /assets/Music.jpg 1600w');
  });
});

describe('a cover', () => {
  it('is a placeholder, hidden from screen readers, while there is no photo', () => {
    const { container } = show('Tabletop');
    expect(container.firstChild).toHaveClass('placeholder-cover', 'placeholder-cover-tabletop');
    expect(screen.getByText('Licensed photo · Tabletop')).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('img')).toBeNull();
  });

  it('names no tag on the default placeholder', () => {
    const { container } = show('Karaoke');
    expect(container.firstChild).toHaveClass('placeholder-cover-default');
    expect(screen.getByText('Licensed photo')).toBeInTheDocument();
  });

  it('shows the photo as decoration: empty alt text, lazy and responsive', () => {
    addPhoto('Food');
    const { container } = show('Food');
    const img = container.querySelector('img');
    expect(img).toHaveAttribute('alt', '');
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('src', '/assets/Food.jpg');
    expect(img).toHaveAttribute('srcset', '/assets/Food-800.jpg 800w, /assets/Food.jpg 1600w');
    expect(img).toHaveAttribute('sizes', '100vw');
    expect(screen.queryByText(/Licensed photo/)).toBeNull();
  });

  it('falls back to the placeholder when the photo is missing, and tries the next tag’s', () => {
    addPhoto('Food');
    addPhoto('Panel');
    const { container, rerender } = show('Food');
    fireEvent.error(container.querySelector('img')!);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('Licensed photo · Food')).toBeInTheDocument();

    rerender(<EventCover tag="Panel" sizes="100vw" className="relative" />);
    expect(container.querySelector('img')).toHaveAttribute('src', '/assets/Panel.jpg');
  });
});
