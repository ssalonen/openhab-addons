// Keep cleanup around the initial render as well as asynchronous operations.
export async function runInteraction({ start, action, succeeded, failed, finished }) {
  try { start(); await action(); succeeded(); }
  catch (error) { failed(error); }
  finally { finished(); }
}

// Resolve stable action identity against new controls without imitating a DOM in tests.
export function restoreActionFocus(key, { focusAction, focusResult }) {
  if (key !== undefined && !focusAction(key)) focusResult();
}

export function cutoverBlockReason(report, sourcePollerUid, comparison) {
  if (!report) return 'Load a reviewed workflow first.';
  if (report.status === 'STAGED' && comparison?.previewIdentity === report.preview?.identity
      && comparison.sourcePollerUid === sourcePollerUid
      && report.preview.groups.some(group => group.sourcePollerUid === sourcePollerUid)
      && comparison.outcome === 'MATCH' && Array.isArray(comparison.comparisons) && comparison.comparisons.length > 0
      && comparison.comparisons.every(entry => entry?.outcome === 'MATCH')) return '';
  return report.readinessReason || 'Validate this exact staged group in this session before requesting cutover.';
}
// Presentation only: exact sanitized server messages, never an eligibility decision.
const reviewCategories = {
  writes: ['Write behavior', 'The migrator does not convert command or write behavior automatically.', 'Review commands, write transforms and device behavior manually; keep the existing setup until tested.'],
  channels: ['Multiple channel contracts', 'Distinct source channels cannot be merged into one channel contract by this migrator.', 'Review each source channel and its Item links separately.'],
  conversion: ['Conversion unproven', 'The source type or its conversion has not been proven compatible.', 'Check channel types, Item types and conversions before designing a manual migration.'],
  profile: ['Link profile review', 'The read may have a candidate, but this Item link profile was not proven compatible; its target link is not planned.', 'Review the owning Item link and profile configuration manually.'],
  parent: ['Parent topology review', 'The legacy data parent is not a poller; this topology is outside automatic migration coverage.', 'Review the parent topology and any write behavior manually.'],
  target: ['Existing target', 'A target poller2 Thing already exists; this review did not create a replacement candidate.', 'Inspect the existing target and its ownership before planning changes; do not delete it blindly.'],
  transform: ['Read transform review', 'A custom read transform is outside automatic migration coverage.', 'Review the transform and prove equivalent decoded values manually.'],
  unknown: ['Other manual review', 'The server reported a case this view cannot classify. No automatic conversion is implied.', 'Review the exact server reason below and the owning configuration manually.']
};
const reviewReasons = new Map([
  ['writes require manual migration; configuration omitted', 'writes'],
  ['JSON write transform requires manual migration; configuration omitted', 'writes'],
  ['distinct source channels require manual migration; channel contracts cannot be collapsed', 'channels'],
  ['source channel type is unknown or its conversion is not proven compatible; requires manual migration', 'conversion'],
  ['Item link profile configuration is not proven compatible; target link was not planned', 'profile'],
  ['legacy data parent is not a poller; writes require manual migration', 'parent'],
  ['target poller2 Thing already exists', 'target'],
  ['custom readTransform requires manual migration; configuration omitted', 'transform']
]);
export function classifyReviewReason(reason) {
  const category = reviewReasons.get(reason) || 'unknown';
  const [label, explanation, nextAction] = reviewCategories[category];
  return { category, label, explanation, nextAction };
}

// Input collections have already passed the controller DTO guards. Never infer a
// Thing UID from a source: profile review entries can identify a channel instead.
export function reviewEntries(...collections) {
  const grouped = new Map();
  for (const work of collections.flat()) {
    const key = JSON.stringify([work.sourceUid, work.reason]);
    if (!grouped.has(key)) grouped.set(key, {
      sourceUid: work.sourceUid, sourceLabel: work.sourceUid.split(':').slice(-2).join(' / ') || work.sourceUid,
      reason: work.reason, ...classifyReviewReason(work.reason), configurationIdentities: []
    });
    const identities = grouped.get(key).configurationIdentities;
    if (typeof work.configurationIdentity === 'string' && work.configurationIdentity && !identities.includes(work.configurationIdentity))
      identities.push(work.configurationIdentity);
  }
  const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  return [...grouped.values()].map(row => ({...row, configurationIdentities: row.configurationIdentities.sort(compare)}))
    .sort((a,b) => compare(a.sourceUid,b.sourceUid) || compare(a.reason,b.reason));
}
export function reviewOverview(plan, manualWork = []) {
  return {
    candidateGroups: plan ? plan.groups.length : null,
    candidateChannels: plan && plan.groups.every(group => Array.isArray(group.channels))
      ? plan.groups.reduce((count, group) => count + group.channels.length, 0) : null,
    plannedLinks: plan ? plan.impacts.filter(impact => impact.targetLinkPlanned).length : null,
    manualReviewEntries: reviewEntries(manualWork, plan?.manualWork || []).length
  };
}

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function stringFields(value, keys) {
  return record(value) && keys.every(key => typeof value[key] === 'string');
}
function manualWorkValid(work) {
  return Array.isArray(work) && work.every(entry => stringFields(entry, ['sourceUid','reason']));
}
function workflowValid(report) {
  // Check renderer inputs, not unused backend internals or opaque profile values.
  const plan = report?.preview;
  return stringFields(report, ['status']) && stringFields(plan, ['identity'])
    && (plan.yaml === undefined || typeof plan.yaml === 'string')
    && (report.validation === undefined || typeof report.validation === 'string')
    && (report.stagedAt == null || typeof report.stagedAt === 'string')
    && (report.switchedLinks === undefined || (Array.isArray(report.switchedLinks)
      && report.switchedLinks.every(link => typeof link === 'string')))
    && Array.isArray(plan.groups) && plan.groups.every(group =>
      stringFields(group, ['sourcePollerUid','targetPollerUid','targetBridgeUid']))
    && Array.isArray(plan.impacts) && plan.impacts.every(impact =>
      stringFields(impact, ['itemName','sourceChannelUid','targetChannelUid'])
      && typeof impact.targetLinkPlanned === 'boolean')
    && manualWorkValid(plan.manualWork);
}
function comparisonValid(observation) {
  // Validate only the detached fields consumed by the renderer. Empty values and
  // absent timestamps are legitimate UNAVAILABLE placeholders, not schema errors.
  const timestamp = value => value == null || typeof value === 'string';
  return stringFields(observation, ['previewIdentity','sourcePollerUid','outcome'])
    && timestamp(observation.observedAt)
    && Array.isArray(observation.comparisons) && observation.comparisons.every(entry =>
      stringFields(entry, ['sourceChannelUid','targetChannelUid','address','valueType','sourceValue','targetValue','outcome','reason'])
      && timestamp(entry.sourceObservedAt) && timestamp(entry.targetObservedAt));
}
function providerProposalValid(proposal) {
  return stringFields(proposal, ['revisionDigest'])
    && Array.isArray(proposal.fileChanges) && proposal.fileChanges.every(change =>
      stringFields(change, ['sourcePollerUid','targetPollerUid','revisionDigest','candidateYaml','instructions'])
      && Array.isArray(change.sources) && change.sources.every(source =>
        stringFields(source, ['sourceUid','providerIdentity','sourceLocation'])
        && typeof (source.configurationDigest ?? source.revisionDigest) === 'string')
      && Array.isArray(change.linkPatches) && change.linkPatches.every(patch =>
        stringFields(patch, ['itemName','sourceChannelUid','targetChannelUid','sourceLocation'])
        && typeof (patch.sourceLinkProviderIdentity ?? patch.providerIdentity) === 'string'
        && typeof (patch.sourceLinkDigest ?? patch.revisionDigest) === 'string'
        && record(patch.sourceProfileConfiguration ?? patch.profileConfiguration)))
    && Array.isArray(proposal.manualWork) && proposal.manualWork.every(work =>
      stringFields(work, ['sourceUid','reason','configurationIdentity']));
}
export function createController({ auth, fetch = globalThis.fetch }) {
  const state = { identity: '', report: null, comparison: null, manualWork: [], providerPreview: null, providerError: '', busy: false, uncertain: false };
  let generation = 0;
  let session = auth.sessionVersion?.();
  function invalidateSession() {
    generation++;
    session = auth.sessionVersion?.();
    Object.assign(state, { identity: '', report: null, comparison: null, manualWork: [], providerPreview: null, providerError: '', busy: false, uncertain: false });
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
    state.comparison = null;
    if (!state.identity) throw new Error('Select a reviewed identity first.');
    state.report = null;
    const report = await request(capture, `workflows/${encodeURIComponent(state.identity)}`);
    check(capture);
    if (!workflowValid(report)) throw new Error('Invalid backend response.');
    state.report = report;
    state.uncertain = false;
    return state.report;
  }
  async function preview() {
    const capture = context();
    if (state.busy) throw new Error('An operation is already in progress.');
    state.comparison = null;
    state.report = null;
    state.manualWork = [];
    state.busy = true;
    try {
      const review = await request(capture, 'previews', 'POST');
      check(capture);
      if (!stringFields(review?.plan, ['identity']) || !manualWorkValid(review.manualWork))
        throw new Error('Invalid backend response.');
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
    if (operation === 'cutover' && (state.identity !== state.report.preview.identity
        || cutoverBlockReason(state.report, body.sourcePollerUid, state.comparison)))
      throw new Error(cutoverBlockReason(state.report, body.sourcePollerUid, state.comparison) || 'Refetch the reviewed identity.');
    if (['validate','cutover'].includes(operation) && !state.report.preview.groups.some(g => g.sourcePollerUid === body.sourcePollerUid))
      throw new Error('Select an explicit reviewed poller group.');
    state.busy = true;
    state.comparison = null;
    const identity = state.identity;
    try {
      const report = await request(capture, `workflows/${encodeURIComponent(state.identity)}/${operation}`, 'POST', body);
      check(capture);
      if (!workflowValid(report)) throw new Error('Invalid backend response.');
      await refresh(capture);
      // POST validation evidence is transient: GET readback does not collect observations.
      // Keep it only for this successfully reconciled validation, never across an explicit refetch.
      if (operation === 'validate' && identity === state.identity && state.report.preview.identity === identity
          && state.report.status === 'STAGED' && report.observation?.previewIdentity === identity
          && report.observation.sourcePollerUid === body.sourcePollerUid && comparisonValid(report.observation)) {
        state.comparison = report.observation;
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
    state.comparison = null;
    state.providerPreview = null;
    state.providerError = '';
    try {
      const proposal = await request(capture, 'provider-previews');
      check(capture);
      if (!providerProposalValid(proposal)) throw new Error('Invalid backend response.');
      state.providerPreview = proposal;
      return proposal;
    } catch (error) {
      if (current(capture)) state.providerError = error.message;
      throw error;
    }
  }
  return { state, preview, refresh, mutate, providerPreviews, invalidateSession };
}
