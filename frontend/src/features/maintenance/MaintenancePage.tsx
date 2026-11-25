/** Shown in place of the screen while the API answers 503 (maintenance mode, D-015 rule 16). */
export function MaintenancePage() {
  return (
    <div role="alert" className="space-y-4">
      <h1 className="text-2xl font-semibold">Back soon</h1>
      <p>Con-Fuzion is down for a short while. Nothing you’ve saved has been lost.</p>
      <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
        Try again
      </button>
    </div>
  );
}
