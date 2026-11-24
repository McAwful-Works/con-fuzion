// A fake Discord for the end-to-end tests. Node built-ins only.
//
// It implements just what the backend calls (see backend/src/test/.../DiscordStub.java for the real shapes):
//   GET  /oauth2/authorize               redirects back with code + the same state (or error=access_denied)
//   POST /api/oauth2/token               checks the PKCE S256 verifier and the client credentials
//   GET  /api/v10/users/@me              the configured user
//   GET  /api/v10/users/@me/guilds       the configured guilds (limit=200 required, like the backend sends)
//
// Tests steer it through /__e2e/* (never reachable from the app; the backend only knows the URIs above):
//   POST /__e2e/scenario   { user, guilds }  who signs in next (snapshotted when /oauth2/authorize is hit)
//   POST /__e2e/deny-next                    the next /oauth2/authorize answers error=access_denied
//   POST /__e2e/reset                        forget scenario, pending denial, codes and tokens

import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

export const STUB_PORT = Number(process.env.E2E_STUB_PORT ?? 4010);
// Dummy values. They match e2e/application-e2e.properties and are not Discord credentials.
export const CLIENT_ID = 'e2e-client-id';
export const CLIENT_SECRET = 'e2e-client-secret';

function pkceChallenge(verifier) {
  return createHash('sha256').update(verifier).digest('base64url');
}

/** Starts the fake and resolves once it is listening. `close()` stops it. */
export function startFakeDiscord(port = STUB_PORT) {
  /** @type {{user: object, guilds: object[]} | null} */
  let scenario = null;
  let denyNext = false;
  /** code -> { challenge, redirectUri, scenario } */
  const codes = new Map();
  /** access token -> scenario */
  const tokens = new Map();

  const json = (res, status, body) => {
    const text = JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(text),
    });
    res.end(text);
  };
  const readBody = async (req) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    return Buffer.concat(chunks).toString('utf8');
  };

  function authorize(url, res) {
    const q = url.searchParams;
    const redirectUri = q.get('redirect_uri');
    const state = q.get('state');
    if (
      q.get('response_type') !== 'code' ||
      q.get('client_id') !== CLIENT_ID ||
      !redirectUri ||
      !state
    ) {
      return json(res, 400, { error: 'invalid_request' });
    }
    const back = new URL(redirectUri);
    back.searchParams.set('state', state);
    if (denyNext) {
      denyNext = false;
      back.searchParams.set('error', 'access_denied');
      res.writeHead(302, { Location: back.toString() });
      return res.end();
    }
    // The backend must send PKCE with S256 (plan B5).
    const challenge = q.get('code_challenge');
    if (!challenge || q.get('code_challenge_method') !== 'S256') {
      return json(res, 400, {
        error: 'invalid_request',
        error_description: 'PKCE S256 required',
      });
    }
    if (!scenario) return json(res, 500, { error: 'no scenario set by the test' });
    const code = randomBytes(16).toString('hex');
    codes.set(code, { challenge, redirectUri, scenario });
    back.searchParams.set('code', code);
    res.writeHead(302, { Location: back.toString() });
    res.end();
  }

  async function token(req, res) {
    const expected = 'Basic ' + Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64');
    if (req.headers.authorization !== expected) return json(res, 401, { error: 'invalid_client' });
    const form = new URLSearchParams(await readBody(req));
    const entry = codes.get(form.get('code') ?? '');
    codes.delete(form.get('code') ?? ''); // single use
    if (
      form.get('grant_type') !== 'authorization_code' ||
      !entry ||
      form.get('redirect_uri') !== entry.redirectUri ||
      !form.get('code_verifier') ||
      pkceChallenge(form.get('code_verifier')) !== entry.challenge
    ) {
      return json(res, 400, { error: 'invalid_grant' });
    }
    const access = randomBytes(16).toString('hex');
    tokens.set(access, entry.scenario);
    json(res, 200, {
      access_token: access,
      token_type: 'Bearer',
      expires_in: 604800,
      refresh_token: randomBytes(16).toString('hex'),
      scope: 'identify guilds',
    });
  }

  function users(url, req, res) {
    const granted = tokens.get((req.headers.authorization ?? '').replace(/^Bearer /, ''));
    if (!granted) return json(res, 401, { message: '401: Unauthorized', code: 0 });
    if (url.pathname.endsWith('/guilds')) {
      // Discord's maximum page size is 200, and the backend asks for exactly that.
      if (url.searchParams.get('limit') !== '200')
        return json(res, 400, { message: 'limit=200 expected' });
      return json(res, 200, granted.guilds);
    }
    json(res, 200, granted.user);
  }

  async function control(url, req, res) {
    if (req.method !== 'POST') return json(res, 405, {});
    switch (url.pathname) {
      case '/__e2e/scenario':
        scenario = JSON.parse(await readBody(req));
        break;
      case '/__e2e/deny-next':
        denyNext = true;
        break;
      case '/__e2e/reset':
        scenario = null;
        denyNext = false;
        codes.clear();
        tokens.clear();
        break;
      default:
        return json(res, 404, {});
    }
    json(res, 200, {});
  }

  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://stub');
    const run = async () => {
      if (url.pathname.startsWith('/__e2e/')) return control(url, req, res);
      if (req.method === 'GET' && url.pathname === '/oauth2/authorize') return authorize(url, res);
      if (req.method === 'POST' && url.pathname === '/api/oauth2/token') return token(req, res);
      if (req.method === 'GET' && url.pathname.startsWith('/api/v10/users/@me'))
        return users(url, req, res);
      json(res, 404, { message: '404: Not Found', code: 0 });
    };
    run().catch((error) => {
      console.error('fake Discord error:', error);
      if (!res.headersSent) json(res, 500, { error: 'fake Discord error' });
    });
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () =>
      resolve({
        port,
        close: () =>
          new Promise((done) => {
            server.closeAllConnections();
            server.close(() => done());
          }),
      }),
    );
  });
}

// `node fake-discord.mjs` runs it by itself, for poking at it by hand.
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const stub = await startFakeDiscord();
  console.log(`fake Discord listening on 127.0.0.1:${stub.port}`);
  for (const signal of ['SIGINT', 'SIGTERM'])
    process.on(signal, () => void stub.close().then(() => process.exit(0)));
}
