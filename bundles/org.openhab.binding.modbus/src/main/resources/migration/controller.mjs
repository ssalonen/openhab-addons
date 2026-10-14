export function cutoverBlockReason(report) {
  if (!report) return 'Load a reviewed workflow first.';
  if (report.status !== 'STAGED' || report.validation !== 'MATCH' || report.cutoverReady !== true)
    return report.readinessReason || 'Fresh binding-owned validation is unavailable; cutover is blocked.';
  return '';
}
export function createController({ auth, fetch = globalThis.fetch }) {
  const state = { identity: '', report: null, manualWork: [], providerPreview: null, providerError: '', busy: false, uncertain: false };
  let generation = 0;
  let session = auth.sessionVersion?.();
  function invalidateSession() {
    generation++;
    session = auth.sessionVersion?.();
    Object.assign(state, { identity: '', report: null, manualWork: [], providerPreview: null, providerError: '', busy: false, uncertain: false });
  }
  function context() {
    if (auth.sessionVersion?.() !== session) invalidateSession();
    return { generation, session };
  }
  function current(capture) {
    if (auth.sessionVersion?.() !== session) invalidateSession();
    return capture.generation === generation && capture.session === session;
  }
  function check(capture) {
    if (!current(capture)) throw new Error('Main UI session changed. Refetch the workflow before making changes.');
  }
  async function request(capture, path, method = 'GET', body) {
    const headers = await auth.headers();
    check(capture);
    let response;
    try {
      response = await fetch(`/rest/modbus/migration/${path}`, {method,
        headers: { ...headers, ...(body === undefined ? {} : {'Content-Type':'application/json'}) },
        ...(body === undefined ? {} : {body: JSON.stringify(body)}), credentials:'same-origin', cache:'no-store'});
    } catch { check(capture); throw new Error('Network request failed.'); }
    check(capture);
    if (response.status === 401) { auth.invalidate(); throw new Error('Session expired. Sign in again in Main UI.'); }
    if (response.status === 403) throw new Error('Forbidden: administrator access is required.');
    if (!response.ok) throw new Error(`Operation refused (HTTP ${response.status}).`);
    let data;
    try { data = await response.json(); } catch { check(capture); throw new Error('Invalid backend response.'); }
    check(capture);
    return data;
  }
  async function refresh(capture = context()) {
    check(capture);
    if (!state.identity) throw new Error('Select a reviewed identity first.');
    state.report = null;
    const report = await request(capture, `workflows/${encodeURIComponent(state.identity)}`);
    check(capture);
    state.report = report;
    state.uncertain = false;
    return state.report;
  }
  async function preview() {
    const capture = context();
    if (state.busy) throw new Error('An operation is already in progress.');
    state.busy = true;
    try {
      const review = await request(capture, 'previews', 'POST');
      check(capture);
      state.identity = review.plan.identity;
      state.manualWork = review.manualWork || [];
      state.report = null;
      await refresh(capture);
    } finally { if (current(capture)) state.busy = false; }
  }
  async function mutate(operation, body = {}, confirmed = false) {
    const capture = context();
    if (!confirmed) throw new Error('Explicit confirmation is required.');
    if (!state.identity || !state.report || state.uncertain) throw new Error('Refetch the workflow before making changes.');
    if (state.busy) throw new Error('An operation is already in progress.');
    if (!['apply','validate','cutover','revert','rollback'].includes(operation)) throw new Error('Unknown operation.');
    if (operation === 'cutover' && cutoverBlockReason(state.report)) throw new Error(cutoverBlockReason(state.report));
    if (['validate','cutover'].includes(operation) && !state.report.preview.groups.some(g => g.sourcePollerUid === body.sourcePollerUid))
      throw new Error('Select an explicit reviewed poller group.');
    state.busy = true;
    try {
      const report = await request(capture, `workflows/${encodeURIComponent(state.identity)}/${operation}`, 'POST', body);
      check(capture);
      state.report = report;
      await refresh(capture);
      // POST validation evidence is transient: GET readback does not collect observations.
      // Keep it only for this successfully reconciled validation, never across an explicit refetch.
      if (operation === 'validate' && report.observation?.previewIdentity === state.identity
          && report.observation.sourcePollerUid === body.sourcePollerUid) {
        state.report = { ...state.report, observation: report.observation };
      }
    } catch (error) {
      if (current(capture)) {
        state.uncertain = true;
        // GET reconciliation is safe; never replay the POST, even after obtaining a new token.
        try { await refresh(capture); } catch { if (current(capture)) state.report = null; }
      }
      throw new Error(`${error.message} The operation outcome is uncertain. Refetch and review workflow state before retrying.`);
    } finally { if (current(capture)) state.busy = false; }
  }
  async function providerPreviews() {
    const capture = context();
    state.providerPreview = null;
    state.providerError = '';
    try {
      const proposal = await request(capture, 'provider-previews');
      check(capture);
      if (!proposal || typeof proposal.revisionDigest !== 'string' || !Array.isArray(proposal.fileChanges)
          || !Array.isArray(proposal.manualWork)
          || proposal.fileChanges.some(change => !change
            || ['sourcePollerUid','targetPollerUid','revisionDigest','candidateYaml','instructions'].some(key => typeof change[key] !== 'string')
            || !Array.isArray(change.sources) || !Array.isArray(change.linkPatches))) throw new Error('Invalid backend response.');
      state.providerPreview = proposal;
      return proposal;
    } catch (error) {
      if (current(capture)) state.providerError = error.message;
      throw error;
    }
  }
  return { state, preview, refresh, mutate, providerPreviews, invalidateSession };
}
