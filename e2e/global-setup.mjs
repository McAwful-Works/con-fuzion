// Starts the fake Discord and the built jar, and waits until the backend is healthy.
// Teardown is in global-teardown.mjs; if setup fails half way it stops what it started before rethrowing.

import { spawn } from 'node:child_process';
import { generateKeyPairSync } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startFakeDiscord, STUB_PORT } from './fake-discord.mjs';
import { stopAll } from './global-teardown.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appPort = Number(process.env.E2E_APP_PORT ?? 8089);
export const runDir = join(here, '.run');

/** The Java 25 launcher: E2E_JAVA, else JAVA_HOME, else `java` on the PATH. */
function javaCommand() {
  if (process.env.E2E_JAVA) return process.env.E2E_JAVA;
  if (process.env.JAVA_HOME) {
    const exe = join(
      process.env.JAVA_HOME,
      'bin',
      process.platform === 'win32' ? 'java.exe' : 'java',
    );
    if (existsSync(exe)) return exe;
  }
  return 'java';
}

/** The jar `./gradlew bootJar` built (never the "-plain" one), or E2E_JAR. */
function jarPath() {
  if (process.env.E2E_JAR) return resolve(process.env.E2E_JAR);
  const libs = resolve(here, '..', 'backend', 'build', 'libs');
  const jars = existsSync(libs)
    ? readdirSync(libs).filter((f) => f.endsWith('.jar') && !f.endsWith('-plain.jar'))
    : [];
  if (jars.length !== 1) {
    throw new Error(
      `Expected exactly one jar in ${libs}, found ${jars.length}. Run ./gradlew bootJar in backend/ first.`,
    );
  }
  return join(libs, jars[0]);
}

/**
 * A throwaway VAPID key pair for this run, in the format the backend reads: base64url, the public key as an
 * uncompressed P-256 point (0x04, x, y) and the private key as its raw 32-byte scalar. It exists only in this
 * process and the jar's environment, never on disk, so there is nothing for a secret scanner to find. Without keys
 * the jar turns push off (`/api/push/key` is a 404).
 */
function vapidKeys() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  const { x, y } = publicKey.export({ format: 'jwk' });
  const { d } = privateKey.export({ format: 'jwk' });
  const point = Buffer.concat([
    Buffer.from([4]),
    Buffer.from(x, 'base64url'),
    Buffer.from(y, 'base64url'),
  ]);
  return {
    CONFUZION_VAPID_PUBLIC_KEY: point.toString('base64url'),
    CONFUZION_VAPID_PRIVATE_KEY: d,
  };
}

/** Refuses to run against a port something else already serves, so a stray backend can't be tested by mistake. */
export function assertPortFree(port) {
  return new Promise((ok, fail) => {
    const probe = createServer();
    probe.once('error', () =>
      fail(
        new Error(
          `Port ${port} is already in use. Stop whatever uses it, or set E2E_APP_PORT / E2E_STUB_PORT / ` +
            'E2E_MAINTENANCE_PORT.',
        ),
      ),
    );
    probe.listen(port, () => probe.close(() => ok()));
  });
}

/**
 * Starts the built jar on `port`, pointed at the fake Discord, and returns the process without waiting for it. It runs
 * from `dir` (so its own logs/ folder lands under the ignored .run/) and writes its output to `dir/backend.log`.
 * `args` are extra Spring arguments and `env` extra environment variables. Stop it with `stopBackend`.
 */
export function spawnBackend({ port, dir = runDir, args = [], env = {} }) {
  mkdirSync(dir, { recursive: true });
  const logFile = join(dir, 'backend.log');
  const log = openSync(logFile, 'w');
  const backend = spawn(
    javaCommand(),
    [
      '-jar',
      jarPath(),
      `--server.port=${port}`,
      `--spring.config.additional-location=file:${join(here, 'application-e2e.properties')}`,
      ...args,
    ],
    {
      cwd: dir,
      stdio: ['ignore', log, log],
      env: { ...process.env, E2E_STUB_PORT: String(STUB_PORT), ...env },
    },
  );
  closeSync(log);
  backend.logFile = logFile;
  backend.once('error', (error) => {
    backend.spawnError = error;
  });
  return backend;
}

/** Resolves once the jar on `port` answers its health check, and throws (with the log's tail) if it doesn't. */
export async function waitForHealth(child, port, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.spawnError)
      throw new Error(`Could not start Java (${javaCommand()}): ${child.spawnError.message}`);
    if (child.exitCode !== null)
      throw new Error(`The backend exited with code ${child.exitCode}.\n${logTail(child.logFile)}`);
    try {
      const response = await fetch(`http://localhost:${port}/api/actuator/health`);
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(
    `The backend was not healthy after ${timeoutMs / 1000}s.\n${logTail(child.logFile)}`,
  );
}

function logTail(logFile) {
  try {
    return readFileSync(logFile, 'utf8').split('\n').slice(-40).join('\n');
  } catch {
    return '(no backend log)';
  }
}

export default async function globalSetup() {
  globalThis.__e2e = { stub: null, backend: null };
  try {
    await assertPortFree(appPort);
    await assertPortFree(STUB_PORT);

    globalThis.__e2e.stub = await startFakeDiscord(STUB_PORT);

    const backend = spawnBackend({ port: appPort, env: vapidKeys() });
    globalThis.__e2e.backend = backend;
    await waitForHealth(backend, appPort);
  } catch (error) {
    await stopAll();
    throw error;
  }
}
