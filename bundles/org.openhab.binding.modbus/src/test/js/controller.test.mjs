import test from 'node:test';
import assert from 'node:assert/strict';
import { createController, cutoverBlockReason } from '../../main/resources/migration/controller.mjs';

test('failed GET invalidates stale report and blocks further mutations', async () => {
  let unavailable = false;
  const f = fixture(url => {
    if (unavailable) return new Response('{}', {status:503});
    return new Response(JSON.stringify(url.endsWith('previews') ? {plan,manualWork:[]} : report));
  });
  await f.controller.preview(); unavailable = true;
  await assert.rejects(f.controller.refresh(), /503/);
  assert.equal(f.controller.state.report, null);
  await assert.rejects(f.controller.mutate('apply', {}, true), /Refetch/);
});

test('explicit group and cutover gates prevent any unsafe POST', async () => {
  const f = fixture(); await f.controller.preview();
  const before = f.calls.length;
  await assert.rejects(f.controller.mutate('cutover', {sourcePollerUid:'modbus:poller:plant'}, true), /No fresh comparison/);
  await assert.rejects(f.controller.mutate('validate', {sourcePollerUid:'other'}, true), /explicit reviewed/);
  assert.equal(f.calls.length, before);
});

test('revert and rollback preserve reviewed identifiers in JSON bodies', async () => {
  const f = fixture(); await f.controller.preview();
  const body = {itemName:'Temperature',sourceChannelUid:'modbus:data:plant:temperature:number'};
  await f.controller.mutate('revert',body,true);
  assert.deepEqual(JSON.parse(f.calls[2].init.body), body);
  await f.controller.mutate('rollback',{},true);
  assert.equal(f.calls[4].url.endsWith('/rollback'), true);
});

test('mutation is confirmed, sent once, and read back', async () => {
  const f = fixture(); await f.controller.preview();
  await f.controller.mutate('validate', {sourcePollerUid:'modbus:poller:plant'}, true);
  assert.equal(f.calls[2].url, '/rest/modbus/migration/workflows/review%2F1/validate');
  assert.deepEqual(JSON.parse(f.calls[2].init.body), {sourcePollerUid:'modbus:poller:plant'});
  assert.equal(f.calls[3].init.method, 'GET');
  await assert.rejects(f.controller.mutate('rollback', {}, false), /confirmation/);
});

test('cutover is blocked without explicit available successful validation', () => {
  assert.equal(cutoverBlockReason(report), 'No fresh comparison');
  assert.ok(cutoverBlockReason({...report, cutoverReady:true}));
  assert.ok(cutoverBlockReason(null));
});

test('401 never replays mutation, invalidates access and reports uncertainty', async () => {
  const f = fixture((url, init) => new Response(JSON.stringify(init.method === 'POST' && !url.endsWith('previews') ? {} : url.endsWith('previews') ? {plan, manualWork:[]} : report),
    {status: url.endsWith('/apply') ? 401 : 200}));
  await f.controller.preview();
  await assert.rejects(f.controller.mutate('apply', {}, true), /outcome is uncertain/);
  assert.equal(f.invalidations(), 1);
  assert.equal(f.calls.filter(c => c.url.endsWith('/apply')).length, 1);
  assert.equal(f.calls.at(-1).init.method, 'GET');
});

test('403 shows forbidden and network failure does not replay mutation', async () => {
  for (const failure of ['forbidden','network']) {
    const f = fixture((url) => {
      if (url.endsWith('/rollback')) {
        if (failure === 'network') throw new Error('secret diagnostic');
        return new Response('{}', {status:403});
      }
      return new Response(JSON.stringify(url.endsWith('previews') ? {plan,manualWork:[]} : report));
    });
    await f.controller.preview();
    await assert.rejects(f.controller.mutate('rollback', {}, true), failure === 'forbidden' ? /Forbidden/ : /outcome is uncertain/);
    assert.equal(f.calls.filter(c => c.url.endsWith('/rollback')).length, 1);
  }
});

const plan = { identity: 'review/1', groups: [{sourcePollerUid:'modbus:poller:plant'}], yaml: 'things:', manualWork: [], impacts: [] };
const report = { preview: plan, status: 'STAGED', validation: 'UNAVAILABLE', cutoverReady: false, readinessReason: 'No fresh comparison' };
function fixture(fetch) {
  const calls = [];
  let invalidations = 0;
  const controller = createController({ auth: { headers: async () => ({Authorization:'Bearer access'}), invalidate: () => invalidations++ },
    fetch: async (url, init) => { calls.push({url, init}); return fetch ? fetch(url, init) : new Response(JSON.stringify(url.endsWith('previews') ? {plan, manualWork: []} : report)); } });
  return {controller, calls, invalidations: () => invalidations};
}
test('preview captures using POST then reads authoritative workflow', async () => {
  const f = fixture(); await f.controller.preview();
  assert.equal(f.calls[0].init.method, 'POST');
  assert.equal(f.calls[0].url, '/rest/modbus/migration/previews');
  assert.equal(f.calls[1].init.method, 'GET');
  assert.equal(f.calls[1].url, '/rest/modbus/migration/workflows/review%2F1');
  assert.deepEqual(f.controller.state.report, report);
});
