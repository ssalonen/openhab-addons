export function cutoverBlockReason(report) {
  if (!report) return 'Load a reviewed workflow first.';
  if (report.status !== 'STAGED' || report.validation !== 'MATCH' || report.cutoverReady !== true)
    return report.readinessReason || 'Fresh binding-owned validation is unavailable; cutover is blocked.';
  return '';
}
export function createController({ auth, fetch = globalThis.fetch }) {
  const state = { identity: '', report: null, manualWork: [], busy: false, uncertain: false };
  async function request(path, method = 'GET', body) {
    const headers = await auth.headers();
    let response;
    try {
      response = await fetch(`/rest/modbus/migration/${path}`, {method,
        headers: { ...headers, ...(body === undefined ? {} : {'Content-Type':'application/json'}) },
        ...(body === undefined ? {} : {body: JSON.stringify(body)}), credentials:'same-origin', cache:'no-store'});
    } catch { throw new Error('Network request failed.'); }
    if (response.status === 401) { auth.invalidate(); throw new Error('Session expired. Sign in again in Main UI.'); }
    if (response.status === 403) throw new Error('Forbidden: administrator access is required.');
    if (!response.ok) throw new Error(`Operation refused (HTTP ${response.status}).`);
    try { return await response.json(); } catch { throw new Error('Invalid backend response.'); }
  }
  async function refresh() {
    if (!state.identity) throw new Error('Select a reviewed identity first.');
    state.report = null;
    state.report = await request(`workflows/${encodeURIComponent(state.identity)}`);
    state.uncertain = false;
    return state.report;
  }
  async function preview() {
    if (state.busy) throw new Error('An operation is already in progress.');
    state.busy = true;
    try {
      const capture = await request('previews', 'POST');
      state.identity = capture.plan.identity;
      state.manualWork = capture.manualWork || [];
      state.report = null;
      await refresh();
    } finally { state.busy = false; }
  }
  async function mutate(operation, body = {}, confirmed = false) {
    if (!confirmed) throw new Error('Explicit confirmation is required.');
    if (!state.identity || !state.report || state.uncertain) throw new Error('Refetch the workflow before making changes.');
    if (state.busy) throw new Error('An operation is already in progress.');
    if (!['apply','validate','cutover','revert','rollback'].includes(operation)) throw new Error('Unknown operation.');
    if (operation === 'cutover' && cutoverBlockReason(state.report)) throw new Error(cutoverBlockReason(state.report));
    if (['validate','cutover'].includes(operation) && !state.report.preview.groups.some(g => g.sourcePollerUid === body.sourcePollerUid))
      throw new Error('Select an explicit reviewed poller group.');
    state.busy = true;
    try {
      state.report = await request(`workflows/${encodeURIComponent(state.identity)}/${operation}`, 'POST', body);
      await refresh();
    } catch (error) {
      state.uncertain = true;
      // GET reconciliation is safe; never replay the POST, even after obtaining a new token.
      try { await refresh(); } catch { state.report = null; }
      throw new Error(`${error.message} The operation outcome is uncertain. Refetch and review workflow state before retrying.`);
    } finally { state.busy = false; }
  }
  return { state, preview, refresh, mutate };
}
