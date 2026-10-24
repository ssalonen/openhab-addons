import { createAuthClient } from './auth.mjs';
import { createController, cutoverBlockReason, runInteraction, restoreActionFocus, reviewEntries, reviewOverview } from './controller.mjs';
const auth = createAuthClient();
const controller = createController({ auth });
const $ = id => document.getElementById(id);
let working = false;
let generation = 0;
let focusVersion = 0;
window.addEventListener('focusin', event => {
  if (event.target !== document.body) focusVersion++;
});
const actionControls = new Map();
const actionKey = (...parts) => JSON.stringify(parts);
function sessionChanged() {
  generation++;
  controller.invalidateSession();
  working = false;
  $('identity').value = '';
  $('error').hidden = true;
  render();
  $('status').textContent = 'Main UI session changed. Sign in and refetch the workflow before making changes.';
}
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = String(text);
  if (className) element.className = className;
  return element;
}
function button(text, action, disabled = false, key) {
  const element = node('button', text);
  element.type = 'button'; element.disabled = disabled;
  element.addEventListener('click', action);
  if (key !== undefined) actionControls.set(key, element);
  return element;
}
async function run(action, success = 'Workflow refetched from openHAB. Review the current state below.') {
  if (working) return;
  const version = generation;
  const session = auth.sessionVersion();
  function current() {
    if (version !== generation) return false;
    if (session !== auth.sessionVersion()) { sessionChanged(); return false; }
    return true;
  }
  // Capture before rendering disconnects dynamic controls or disables static ones.
  const initiatingControl = document.activeElement;
  const focusKey = [...actionControls].find(([, element]) => element === initiatingControl)?.[0];
  const capturedFocusVersion = focusVersion;
  await runInteraction({
    start() { working = true; $('error').hidden = true; render(); $('status').textContent = 'Waiting for openHAB…'; },
    action,
    succeeded() { if (current()) $('status').textContent = success; },
    failed(error) { if (current()) { $('error').textContent = error.message; $('error').hidden = false; $('status').textContent = 'Operation stopped. No mutation was automatically replayed.'; } },
    finished() {
      if (!current()) return;
      // Automatic blur to BODY retains ownership; intentional focus elsewhere
      // relinquishes it, even if that element subsequently blurs back to BODY.
      const ownsFocus = capturedFocusVersion === focusVersion
        && (document.activeElement === document.body || document.activeElement === initiatingControl);
      working = false;
      render();
      if (!ownsFocus) return;
      restoreActionFocus(focusKey, {
        focusAction(key) {
          const control = actionControls.get(key);
          if (!control || control.disabled) return false;
          control.focus(); return true;
        },
        focusResult() { $('status').focus(); }
      });
    }
  });
}
function mutate(operation, body, message) {
  if (confirm(message)) return run(() => controller.mutate(operation, body, true));
}
function renderReviewRows(root, entries, emptyText) {
  const previous = root.reviewDetails || new Map();
  const details = new Map();
  function detail(key, label, paragraphs) {
    const element = node('details');
    element.open = previous.get(key)?.open || false;
    element.append(node('summary',label), ...paragraphs.map(text => node('p',text)));
    details.set(key,element);
    return element;
  }
  root.replaceChildren();
  root.reviewDetails = details;
  for (const entry of entries) {
    const key = JSON.stringify([entry.sourceUid,entry.reason]);
    const row = node('tr');
    const source = node('td');
    source.append(node('strong',entry.sourceLabel), detail(`${key}:source`, 'Full source ID',
      [entry.sourceUid, ...entry.configurationIdentities.map(identity => `Configuration digest: ${identity}`)]));
    const classification = node('td');
    classification.append(node('span',entry.label,`badge badge-${entry.category}`));
    const reason = node('td');
    reason.append(node('p',entry.explanation), detail(`${key}:reason`, 'Exact server reason', [entry.reason]));
    row.append(source,classification,reason,node('td',entry.nextAction));
    root.append(row);
  }
  if (!entries.length) {
    const row = node('tr'); const cell = node('td',emptyText); cell.colSpan = 4;
    row.append(cell); root.append(row);
  }
}
function renderReviewOverview(plan) {
  const counts = reviewOverview(plan,controller.state.manualWork);
  $('overview').replaceChildren();
  for (const [label,value] of [['Candidate groups',counts.candidateGroups],['Candidate channels',counts.candidateChannels],
    ['Planned Item links',counts.plannedLinks],['Manual review entries',counts.manualReviewEntries]]) {
    $('overview').append(node('dt',label),node('dd',value === null ? 'Not reported' : value));
  }
  $('link-coverage').textContent = !plan ? 'Load a reviewed plan to see Item link coverage.'
    : counts.plannedLinks === 0 ? 'No fully automatic Item migrations in this review: 0 planned Item links. Candidate channels are read proposals only; any listed Item links still require manual review.'
    : 'Planned Item links are proposals, not completed migrations. Staging does not switch them; explicit validation and server checks still apply.';
  const entries = reviewEntries(controller.state.manualWork,plan?.manualWork || []);
  renderReviewRows($('manual'), entries, plan ? 'No manual review entries reported by this review.' : 'Capture or load a review to see manual review entries.');
  $('review-counts').replaceChildren();
  const categories = new Map();
  for (const entry of entries) {
    if (!categories.has(entry.category)) categories.set(entry.category,{label:entry.label,count:0});
    categories.get(entry.category).count++;
  }
  for (const [category,{label,count}] of [...categories].sort(([a],[b]) => a < b ? -1 : a > b ? 1 : 0))
    $('review-counts').append(node('span',`${label}: ${count} review entries`,`badge badge-${category}`));
}
function renderProviders() {
  const { providerPreview, providerError } = controller.state;
  $('provider-refresh').disabled = working;
  $('provider-status').textContent = providerError ? `Provider proposals unavailable: ${providerError}`
    : providerPreview ? 'Detached proposals loaded for manual review only.' : 'No provider proposals loaded.';
  const root = $('provider-proposals');
  root.replaceChildren();
  if (!providerPreview) return;
  const version = generation;
  const session = auth.sessionVersion();
  function exportCandidate(action, message) {
    if (version !== generation) return;
    if (session !== auth.sessionVersion()) { sessionChanged(); return; }
    return run(action, message);
  }
  root.append(node('p', `Review revision: ${providerPreview.revisionDigest}`));
  if (!providerPreview.fileChanges.length) root.append(node('p', 'No eligible candidate YAML reported. Review any manual work below.'));
  for (const change of providerPreview.fileChanges) {
    const section = node('div', undefined, 'group');
    section.append(node('h3', change.sourcePollerUid), node('p', `Unlinked candidate: ${change.targetPollerUid}`),
      node('p', `Proposal revision: ${change.revisionDigest}`));
    for (const source of change.sources || []) section.append(node('p',
      `Source: ${source.sourceUid} · Provider: ${source.providerIdentity} · Location: ${source.sourceLocation} · Configuration digest: ${source.configurationDigest ?? source.revisionDigest}`));
    section.append(node('pre', change.candidateYaml));
    const actions = node('div', undefined, 'actions');
    actions.append(button('Copy candidate YAML', () => exportCandidate(async () => {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable. Select the displayed YAML or download the candidate instead.');
      await navigator.clipboard.writeText(change.candidateYaml);
    }, 'Candidate YAML copied. No configuration was changed.'), working, actionKey('copy',change.sourcePollerUid,change.revisionDigest)),
    button('Download candidate YAML', () => exportCandidate(() => {
      const url = URL.createObjectURL(new Blob([change.candidateYaml], {type:'application/yaml;charset=utf-8'}));
      try {
        const link = node('a');
        link.href = url; link.download = 'modbus-poller2-candidate.yaml';
        link.click();
      } finally { URL.revokeObjectURL(url); }
    }, 'Candidate YAML download requested. This is not the source filename; no configuration was changed.'), working, actionKey('download',change.sourcePollerUid,change.revisionDigest)));
    section.append(actions, node('p', change.instructions));
    for (const patch of change.linkPatches || []) {
      const profile = patch.sourceProfileConfiguration ?? patch.profileConfiguration;
      const keys = profile && typeof profile === 'object' && !Array.isArray(profile) ? Object.keys(profile) : null;
      const safeProfile = keys && (keys.length === 0 || (keys.length === 1 && profile.profile === 'system:default'));
      section.append(node('p',
        `Manual link patch: ${patch.itemName} · Remove ${patch.sourceChannelUid}, then add ${patch.targetChannelUid} · Provider: ${patch.sourceLinkProviderIdentity ?? patch.providerIdentity} · Source link digest: ${patch.sourceLinkDigest ?? patch.revisionDigest} · Source profile: ${safeProfile ? JSON.stringify(profile) : 'profile configuration omitted; review and restore the owning configuration manually'}`));
    }
    root.append(section);
  }
  const manual = node('ul');
  for (const work of providerPreview.manualWork) manual.append(node('li',
    `${work.sourceUid}: ${work.reason} · Configuration digest: ${work.configurationIdentity}`));
  root.append(node('h3', 'Provider manual work'), manual);
}
function observedAt(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value)) && Date.parse(value) !== 0
    ? value : 'Not observed';
}
function renderComparisons(report) {
  const root = $('comparisons');
  root.replaceChildren();
  const observation = controller.state.comparison;
  if (!observation || !Array.isArray(observation.comparisons) || !observation.comparisons.length) {
    root.append(node('p', 'No detailed comparison evidence returned. Validate a staged group; aggregate status alone is not a per-channel report.'));
    return;
  }
  root.append(node('p', `Last explicit selected-group comparison: ${observation.outcome} · Source poller: ${observation.sourcePollerUid} · Report observed at: ${observedAt(observation.observedAt)}`));
  const group = report.preview.groups.find(group => group.sourcePollerUid === observation.sourcePollerUid);
  if (group) root.append(node('p', `Candidate Thing ID: ${group.targetPollerUid}`));
  for (const comparison of observation.comparisons) {
    const section = node('div', undefined, 'group');
    section.append(node('h3', comparison.outcome),
      node('p', `Source channel: ${comparison.sourceChannelUid}`),
      node('p', `Target channel: ${comparison.targetChannelUid}`),
      node('p', `Address: ${comparison.address} · Value type: ${comparison.valueType}`),
      node('p', `Source value: ${comparison.sourceValue} · Source observed at: ${observedAt(comparison.sourceObservedAt)}`),
      node('p', `Target value: ${comparison.targetValue} · Target observed at: ${observedAt(comparison.targetObservedAt)}`),
      node('p', `Reason: ${comparison.reason}`));
    root.append(section);
  }
}
function render() {
  actionControls.clear();
  for (const id of ['preview','refresh','provider-refresh','stage','rollback'])
    actionControls.set(actionKey('static',id), $(id));
  renderProviders();
  const { report, identity, uncertain } = controller.state;
  renderComparisons(report);
  const plan = report?.preview;
  const locked = working || uncertain || !report;
  $('preview').disabled = working;
  $('refresh').disabled = working;
  $('identity').disabled = working;
  if (identity) $('identity').value = identity;
  $('stage').disabled = locked || report?.status !== 'PREVIEW';
  $('rollback').disabled = locked || !['STAGED','RECOVERY'].includes(report?.status);
  $('summary').replaceChildren();
  if (report) for (const [label, value] of [['Identity',plan.identity],['Stage',report.status],['Staged at',observedAt(report.stagedAt)],['Read-only report validation (GET does not validate)',report.validation],['Switched links',(report.switchedLinks || []).join(', ') || 'None reported']])
    $('summary').append(node('dt',label),node('dd',value));
  $('yaml').textContent = plan?.yaml || 'No reviewed plan loaded.';
  renderReviewOverview(plan);
  $('readiness').textContent = 'A last explicit MATCH permits requesting cutover only for that selected staged group. The server performs new fresh reads and can refuse; retained comparisons are not authorization. Read-only GET does not validate. Refetch or another operation clears the comparison.';
  $('groups').replaceChildren();
  for (const group of plan?.groups || []) {
    const reason = cutoverBlockReason(report, group.sourcePollerUid, controller.state.comparison);
    const section = node('div', undefined, 'group');
    section.append(node('h3', group.sourcePollerUid), node('p', `Candidate: ${group.targetPollerUid} · Bridge: ${group.targetBridgeUid}`));
    const actions = node('div', undefined, 'actions');
    actions.append(button('Validate this group', () => mutate('validate', { sourcePollerUid: group.sourcePollerUid }, `Request fresh validation of ${group.sourcePollerUid}? Missing evidence remains blocked.`), locked || report.status !== 'STAGED', actionKey('validate',group.sourcePollerUid)),
      button('Request cutover for this group', () => mutate('cutover', { sourcePollerUid: group.sourcePollerUid }, `Request switching reviewed Item links for ${group.sourcePollerUid}? The server performs new fresh reads and can refuse if evidence or lifecycle has changed.`), locked || !!reason, actionKey('cutover',group.sourcePollerUid)));
    section.append(actions); if (reason) section.append(node('p', `Cutover request disabled: ${reason}`));
    $('groups').append(section);
  }
  $('impacts').replaceChildren();
  for (const impact of plan?.impacts || []) {
    const row = node('tr');
    for (const value of [impact.itemName, impact.sourceChannelUid, impact.targetChannelUid, impact.targetLinkPlanned ? 'Link planned' : 'Manual review']) row.append(node('td',value));
    const recovery = node('td');
    recovery.append(button(`Revert ${impact.itemName}`, () => mutate('revert', {itemName:impact.itemName, sourceChannelUid:impact.sourceChannelUid}, `Restore ${impact.itemName} to ${impact.sourceChannelUid}? The backend verifies ownership before changing links.`), locked || !['STAGED','RECOVERY'].includes(report.status), actionKey('revert',impact.itemName,impact.sourceChannelUid)));
    row.append(recovery); $('impacts').append(row);
  }
}
$('provider-refresh').addEventListener('click', () => run(() => controller.providerPreviews(), 'Provider proposals refetched for manual review. No configuration was changed.'));
$('preview').addEventListener('click', () => run(() => controller.preview()));
$('identity').addEventListener('input', () => {
  if ($('identity').value !== controller.state.identity) {
    controller.state.comparison = null;
    controller.state.report = null;
    controller.state.manualWork = [];
    controller.state.identity = '';
    render();
  }
});
$('refresh').addEventListener('click', () => {
  const identity = $('identity').value.trim();
  return run(async () => {
    if (identity !== controller.state.identity) { controller.state.report = null; controller.state.manualWork = []; }
    controller.state.identity = identity;
    await controller.refresh();
  });
});
$('stage').addEventListener('click', () => mutate('apply', {}, `Stage reviewed identity ${controller.state.identity}? Candidates may poll alongside legacy Things immediately, increasing device reads; Item links do not switch.`));
$('rollback').addEventListener('click', () => mutate('rollback', {}, `Rollback reviewed identity ${controller.state.identity}? Candidate removal is subject to backend safety checks.`));
window.addEventListener('storage', event => {
  if (event.key === 'openhab.ui:refreshToken' || event.key === null) {
    auth.invalidate(); sessionChanged();
  }
});
render();
