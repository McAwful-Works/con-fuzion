// A few direct reads and writes to the backend's Postgres, for what the UI and API can't do: ending an offer's window
// early (its minimum is 10 minutes, a database CHECK) and checking what the backend stored. It shells out to the `psql`
// CLI so the suite needs no database driver. The credentials are the local-only ones in infra/docker-compose.yml and the
// CI Postgres service (the same CONFUZION_DB_* variables the backend reads). With no `psql` on the PATH (a developer
// machine) it runs the one inside the compose file's postgres container instead.

import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const compose = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'infra',
  'docker-compose.yml',
);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const env = (name, fallback) => process.env[`CONFUZION_DB_${name}`] ?? fallback;

/** Runs one statement and returns its output rows as lines (unaligned, no header). */
function psql(statement) {
  const args = ['-X', '-q', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', statement];
  const user = env('USERNAME', 'confuzion');
  const database = env('NAME', 'confuzion');
  let out;
  try {
    out = execFileSync(
      'psql',
      [
        '-h',
        env('HOST', 'localhost'),
        '-p',
        env('PORT', '5432'),
        '-U',
        user,
        '-d',
        database,
        ...args,
      ],
      {
        env: { ...process.env, PGPASSWORD: env('PASSWORD', 'confuzion') },
        encoding: 'utf8',
      },
    );
  } catch (error) {
    // The container is only the stand-in for a local database; a database somewhere else needs a real psql.
    if (error.code !== 'ENOENT' || process.env.CONFUZION_DB_HOST) throw error;
    out = execFileSync(
      'docker',
      [
        'compose',
        '-f',
        compose,
        'exec',
        '-T',
        'postgres',
        'psql',
        '-U',
        user,
        '-d',
        database,
        ...args,
      ],
      { encoding: 'utf8' },
    );
  }
  return out.split('\n').filter(Boolean);
}

/** The IDs below go into SQL text, so only a UUID (what the API hands out) is accepted. */
function uuid(value) {
  if (!UUID.test(value)) throw new Error(`Not a UUID: ${value}`);
  return value;
}

/**
 * Pushes the user's open offer on the event (public ID) into the past, as if its window had run out. Returns how many
 * offers it changed. D-014 rule 15 makes reads treat it as gone at once; only the expiry job ends it and offers the spot on.
 */
export function expireOffer(eventPublicId, userId) {
  const rows = psql(
    `UPDATE rsvp SET offer_expires_at = now() - interval '1 minute'
      WHERE status = 'OFFERED' AND user_id = '${uuid(userId)}'
        AND event_id = (SELECT id FROM event WHERE public_id = '${uuid(eventPublicId)}')
      RETURNING id`,
  );
  return rows.length;
}

/** The endpoints the user has stored as push subscriptions. */
export const pushEndpoints = (userId) =>
  psql(`SELECT endpoint FROM push_subscription WHERE user_id = '${uuid(userId)}' ORDER BY id`);
