import test from 'node:test';
import assert from 'node:assert/strict';
import { createAuthClient } from '../../main/resources/migration/auth.mjs';
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
function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}
function sessionFixture() {
  let session = 'first-user';
  const waiting = [];
  const auth = createAuthClient({storage: {getItem: () => session}, origin: 'https://openhab.test', cookie: () => '',
    fetch: async () => new Response(JSON.stringify({access_token: session, expires_in: 60}))});
  const controller = createController({auth, fetch: (url, init) => {
    const response = deferred();
    waiting.push({url, init, response});
    return response.promise;
  }});
  return {auth, controller, waiting, change: value => { session = value; }};
}
async function sent(f, count) {
  for (let i = 0; i < 30 && f.waiting.length < count; i++) await Promise.resolve();
  assert.equal(f.waiting.length, count);
}
test('late workflow GET cannot restore privileged data after storage logout', async () => {
  const f = sessionFixture();
  f.controller.state.identity = plan.identity;
  const pending = f.controller.refresh();
  await sent(f, 1);
  f.change(null); f.auth.invalidate(); f.controller.state.report = null;
  f.waiting[0].response.resolve(new Response(JSON.stringify(report)));
  await assert.rejects(pending, /session|Session|Sign in/);
  assert.equal(f.controller.state.report, null);
  assert.ok(cutoverBlockReason(f.controller.state.report));
  await assert.rejects(f.controller.mutate('rollback', {}, true));
  assert.equal(f.waiting.length, 1);
});

test('same-tab session loss blocks the next mutation without a request', async () => {
  const f = sessionFixture();
  f.controller.state.identity = plan.identity;
  f.controller.state.report = report;
  f.controller.state.manualWork = [{sourceUid:'private',reason:'secret'}];
  f.change(null);
  await assert.rejects(f.controller.mutate('rollback', {}, true), /Refetch/);
  assert.equal(f.waiting.length, 0);
  assert.equal(f.controller.state.report, null);
  assert.equal(f.controller.state.identity, '');
  assert.deepEqual(f.controller.state.manualWork, []);
});

test('old preview and mutation settlements preserve the new session busy state', async () => {
  for (const operation of ['preview', 'apply']) for (const status of [200, 401, 503]) {
    const f = sessionFixture();
    f.controller.state.identity = plan.identity;
    f.controller.state.report = report;
    const old = operation === 'preview' ? f.controller.preview() : f.controller.mutate(operation, {}, true);
    await sent(f, 1);
    f.change('second-user'); f.auth.invalidate(); f.controller.invalidateSession();
    const newer = f.controller.preview();
    await sent(f, 2);
    const version = f.auth.sessionVersion();
    f.waiting[0].response.resolve(new Response(JSON.stringify(operation === 'preview' ? {plan,manualWork:[{sourceUid:'private'}]} : report), {status}));
    await assert.rejects(old, /session changed/);
    assert.equal(f.controller.state.busy, true);
    assert.equal(f.controller.state.identity, '');
    assert.equal(f.controller.state.report, null);
    assert.equal(f.controller.state.uncertain, false);
    assert.deepEqual(f.controller.state.manualWork, []);
    assert.equal(f.auth.sessionVersion(), version);
    assert.equal(f.waiting.length, 2, 'stale mutation must not reconcile in the new session');
    f.change(null); f.auth.invalidate(); f.controller.invalidateSession();
    f.waiting[1].response.resolve(new Response(JSON.stringify({plan,manualWork:[]})));
    await assert.rejects(newer, /session changed/);
    assert.equal(f.controller.state.busy, false);
    assert.equal(f.waiting.length, 2);
  }
});

test('logout during preview or mutation readback discards the follow-up GET', async () => {
  for (const operation of ['preview', 'apply']) {
    const f = sessionFixture();
    f.controller.state.identity = plan.identity;
    f.controller.state.report = report;
    const pending = operation === 'preview' ? f.controller.preview() : f.controller.mutate(operation, {}, true);
    await sent(f, 1);
    f.waiting[0].response.resolve(new Response(JSON.stringify(operation === 'preview' ? {plan,manualWork:[]} : report)));
    await sent(f, 2);
    assert.equal(f.waiting[1].init.method, 'GET');
    f.change(null); f.auth.invalidate(); f.controller.invalidateSession();
    f.waiting[1].response.resolve(new Response(JSON.stringify(report)));
    await assert.rejects(pending, /session changed/);
    assert.equal(f.controller.state.report, null);
    assert.equal(f.controller.state.busy, false);
    assert.equal(f.waiting.length, 2);
  }
});

test('logout while decoding a workflow response cannot publish it', async () => {
  const f = sessionFixture();
  f.controller.state.identity = plan.identity;
  const pending = f.controller.refresh();
  await sent(f, 1);
  const decoding = deferred();
  const started = deferred();
  f.waiting[0].response.resolve({ok:true, status:200, json: () => { started.resolve(); return decoding.promise; }});
  await started.promise;
  f.change(null); f.auth.invalidate(); f.controller.invalidateSession();
  decoding.resolve(report);
  await assert.rejects(pending, /session changed/);
  assert.equal(f.controller.state.report, null);
});

test('preview captures using POST then reads authoritative workflow', async () => {
  const f = fixture(); await f.controller.preview();
  assert.equal(f.calls[0].init.method, 'POST');
  assert.equal(f.calls[0].url, '/rest/modbus/migration/previews');
  assert.equal(f.calls[1].init.method, 'GET');
  assert.equal(f.calls[1].url, '/rest/modbus/migration/workflows/review%2F1');
  assert.deepEqual(f.controller.state.report, report);
});

test('provider proposals use a detached read-only GET without changing managed review', async () => {
  const proposal = {revisionDigest:'provider-revision',fileChanges:[],manualWork:[{sourceUid:'file-source',reason:'Unsupported conversion',configurationIdentity:''}]};
  const f = fixture(() => new Response(JSON.stringify(proposal)));
  f.controller.state.identity = plan.identity; f.controller.state.report = report;
  await f.controller.providerPreviews();
  assert.deepEqual(f.controller.state.providerPreview, proposal);
  assert.equal(f.controller.state.report, report);
  assert.equal(f.controller.state.identity, plan.identity);
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, '/rest/modbus/migration/provider-previews');
  assert.equal(f.calls[0].init.method, 'GET');
  assert.equal(f.calls[0].init.body, undefined);
});
