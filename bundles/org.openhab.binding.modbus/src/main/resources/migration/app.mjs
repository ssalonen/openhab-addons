import { createAuthClient } from './auth.mjs';
import { createController, cutoverBlockReason } from './controller.mjs';
const auth = createAuthClient();
const controller = createController({ auth });
const $ = id => document.getElementById(id);
let working = false;
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = String(text);
  if (className) element.className = className;
  return element;
}
function button(text, action, disabled = false) {
  const element = node('button', text);
  element.type = 'button'; element.disabled = disabled;
  element.addEventListener('click', action);
  return element;
}
async function run(action) {
  if (working) return;
  working = true; $('error').hidden = true;
  render(); $('status').textContent = 'Waiting for openHAB…';
  try { await action(); $('status').textContent = 'Workflow refetched from openHAB. Review the current state below.'; }
  catch (error) { $('error').textContent = error.message; $('error').hidden = false; $('status').textContent = 'Operation stopped. No mutation was automatically replayed.'; }
  finally { working = false; render(); }
}
function mutate(operation, body, message) {
  if (confirm(message)) return run(() => controller.mutate(operation, body, true));
}
function render() {
  const { report, identity, uncertain } = controller.state;
  const plan = report?.preview;
  const locked = working || uncertain || !report;
  $('preview').disabled = working;
  $('refresh').disabled = working;
  $('identity').disabled = working;
  if (identity) $('identity').value = identity;
  $('stage').disabled = locked || report?.status !== 'PREVIEW';
  $('rollback').disabled = locked || !['STAGED','RECOVERY'].includes(report?.status);
  $('summary').replaceChildren();
  if (report) for (const [label, value] of [['Identity',plan.identity],['Stage',report.status],['Staged at',report.stagedAt],['Validation',report.validation],['Switched links',(report.switchedLinks || []).join(', ') || 'None reported']])
    $('summary').append(node('dt',label),node('dd',value));
  $('yaml').textContent = plan?.yaml || 'No reviewed plan loaded.';
  $('manual').replaceChildren();
  const manual = [...(controller.state.manualWork || []), ...(plan?.manualWork || [])];
  const seen = new Set();
  for (const work of manual) { const text = `${work.sourceUid}: ${work.reason}`; if (!seen.has(text)) { $('manual').append(node('li', text)); seen.add(text); } }
  if (!manual.length) $('manual').append(node('li', plan ? 'No manual work reported by this review.' : 'Capture or load a review to see manual work.'));
  const reason = cutoverBlockReason(report);
  $('readiness').textContent = reason || 'Backend reports fresh matching validation and cutover readiness. Select a reviewed group explicitly.';
  $('groups').replaceChildren();
  for (const group of plan?.groups || []) {
    const section = node('div', undefined, 'group');
    section.append(node('h3', group.sourcePollerUid), node('p', `Candidate: ${group.targetPollerUid} · Bridge: ${group.targetBridgeUid}`));
    const actions = node('div', undefined, 'actions');
    actions.append(button('Validate this group', () => mutate('validate', { sourcePollerUid: group.sourcePollerUid }, `Request fresh validation of ${group.sourcePollerUid}? Missing evidence remains blocked.`), locked || report.status !== 'STAGED'),
      button('Cut over this group', () => mutate('cutover', { sourcePollerUid: group.sourcePollerUid }, `Switch reviewed Item links for ${group.sourcePollerUid}? Review impact and validation before proceeding.`), locked || !!reason));
    section.append(actions); if (reason) section.append(node('p', `Cutover disabled: ${reason}`));
    $('groups').append(section);
  }
  $('impacts').replaceChildren();
  for (const impact of plan?.impacts || []) {
    const row = node('tr');
    for (const value of [impact.itemName, impact.sourceChannelUid, impact.targetChannelUid, impact.targetLinkPlanned ? 'Link planned' : 'Manual review']) row.append(node('td',value));
    const recovery = node('td');
    recovery.append(button(`Revert ${impact.itemName}`, () => mutate('revert', {itemName:impact.itemName, sourceChannelUid:impact.sourceChannelUid}, `Restore ${impact.itemName} to ${impact.sourceChannelUid}? The backend verifies ownership before changing links.`), locked || !['STAGED','RECOVERY'].includes(report.status)));
    row.append(recovery); $('impacts').append(row);
  }
}
$('preview').addEventListener('click', () => run(() => controller.preview()));
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
    auth.invalidate(); controller.state.report = null; render();
    $('status').textContent = 'Main UI session changed. Sign in and refetch the workflow before making changes.';
  }
});
render();
