const found = import.meta.glob<string>('../../assets/covers/*.{avif,jpg,jpeg,png,webp}', {
  eager: true,
  query: '?url',
  import: 'default',
});

/** The photos in src/assets/covers/: each file name and its built URL. */
export const coverFiles: Record<string, string> = Object.fromEntries(
  Object.entries(found).map(([path, url]) => [path.slice(path.lastIndexOf('/') + 1), url]),
);
