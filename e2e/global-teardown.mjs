// Stops the backend jar and the fake Discord. Runs after the suite whether or not it passed.

/** Stops one jar and resolves once it has exited. Safe with nothing, or with a jar that never started or has gone. */
export async function stopBackend(backend) {
  if (!backend || backend.spawnError || backend.exitCode !== null) return;
  const exited = new Promise((done) => backend.once('exit', done));
  backend.kill('SIGTERM');
  const timedOut = await Promise.race([
    exited.then(() => false),
    new Promise((r) => setTimeout(() => r(true), 15_000)),
  ]);
  if (timedOut) {
    backend.kill('SIGKILL');
    await exited;
  }
}

/** Stops whatever global setup started. Safe to call twice, or when nothing started. */
export async function stopAll() {
  const state = globalThis.__e2e;
  if (!state) return;
  const { backend, stub } = state;
  state.backend = null;
  state.stub = null;

  await stopBackend(backend);
  if (stub) await stub.close();
}

export default async function globalTeardown() {
  await stopAll();
}
