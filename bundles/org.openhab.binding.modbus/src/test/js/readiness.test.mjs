import test from 'node:test';
import assert from 'node:assert/strict';
import { createController, cutoverBlockReason } from '../../main/resources/migration/controller.mjs';

const sourcePollerUid = 'legacy:one';
const other = 'legacy:two';
const report = {preview:{identity:'review',groups:[{sourcePollerUid},{sourcePollerUid:other}]},
  status:'STAGED',validation:'UNAVAILABLE',cutoverReady:false,readinessReason:'No fresh comparison'};
const comparison = {previewIdentity:'review',sourcePollerUid,outcome:'MATCH',comparisons:[{outcome:'MATCH'}]};
function fixture(observation = comparison) {
  const calls = [];
  let session = 1;
  let readback = report;
  const controller = createController({auth:{headers:async () => ({}),sessionVersion:() => session},
    fetch:async (url,init) => {
      calls.push({url,init});
      return new Response(JSON.stringify(url.endsWith('/validate') ? {...report,observation} : readback));
    }});
  controller.state.identity = 'review';
  controller.state.report = report;
  return {controller,calls,logout:() => { session++; controller.invalidateSession(); },readback:value => { readback = value; }};
}
const validate = f => f.controller.mutate('validate',{sourcePollerUid},true);
const request = (f,group = sourcePollerUid) => f.controller.mutate('cutover',{sourcePollerUid:group},true);

test('only explicitly compared staged group can request a new server-guarded cutover after conservative GET',async () => {
  const f = fixture();
  await validate(f);
  assert.equal(f.controller.state.report.validation,'UNAVAILABLE');
  assert.equal(f.controller.state.report.cutoverReady,false);
  assert.equal(cutoverBlockReason(f.controller.state.report,sourcePollerUid,f.controller.state.comparison),'');
  await assert.rejects(request(f,other));
  await request(f);
  assert.equal(f.calls.filter(c => c.url.endsWith('/cutover')).length,1);
  assert.deepEqual(JSON.parse(f.calls.find(c => c.url.endsWith('/cutover')).init.body),{sourcePollerUid});
  assert.equal(f.calls.at(-1).init.method,'GET');
});

test('refetch, session change, identity change and other operations discard selected comparison', async () => {
  for (const action of ['refresh','logout','identity','rollback','provider']) {
    const f = fixture();
    await validate(f);
    if (action === 'refresh') await f.controller.refresh();
    if (action === 'logout') f.logout();
    if (action === 'identity') { f.controller.state.identity = 'different'; await f.controller.refresh(); }
    if (action === 'rollback') await f.controller.mutate('rollback',{},true);
    if (action === 'provider') await f.controller.providerPreviews().catch(() => {});
    assert.equal(f.controller.state.comparison,null,action);
    const count = f.calls.length;
    await assert.rejects(request(f));
    assert.equal(f.calls.length,count,`${action}: blocked without POST`);
  }
});

test('unknown, mismatched, unavailable, empty or inconsistent comparisons cannot enable requests', async () => {
  for (const observation of [undefined,{...comparison,outcome:'MISMATCH'},
    {...comparison,outcome:'UNAVAILABLE'},{...comparison,outcome:'UNKNOWN'},
    {...comparison,previewIdentity:'different'},{...comparison,sourcePollerUid:other},
    {...comparison,comparisons:[]},{...comparison,comparisons:[{outcome:'UNAVAILABLE'}]},
    {...comparison,comparisons:{length:1}},{...comparison,comparisons:[null]}]) {
    const f = fixture(observation === undefined ? null : observation);
    await validate(f);
    assert.doesNotThrow(() => cutoverBlockReason(f.controller.state.report,sourcePollerUid,f.controller.state.comparison));
    const count = f.calls.length;
    await assert.rejects(request(f));
    assert.equal(f.calls.length,count);
  }
});

test('readback identity or lifecycle change prevents retaining POST comparison', async () => {
  for (const readback of [{...report,status:'RECOVERY'},
    {...report,preview:{...report.preview,identity:'different'}}]) {
    const f = fixture(); f.readback(readback);
    await validate(f);
    assert.equal(f.controller.state.comparison,null);
    await assert.rejects(request(f));
  }
});

test('a refused cutover request clears comparison, reconciles once and never replays POST', async () => {
  const calls = [];
  const controller = createController({auth:{headers:async () => ({})},fetch:async (url,init) => {
    calls.push({url,init});
    if (url.endsWith('/cutover')) return new Response('{}',{status:409});
    return new Response(JSON.stringify(url.endsWith('/validate') ? {...report,observation:comparison} : report));
  }});
  controller.state.identity = 'review'; controller.state.report = report;
  await controller.mutate('validate',{sourcePollerUid},true);
  await assert.rejects(controller.mutate('cutover',{sourcePollerUid},true),/409/);
  assert.equal(controller.state.comparison,null);
  assert.equal(calls.filter(c => c.url.endsWith('/cutover')).length,1);
  assert.equal(calls.at(-1).init.method,'GET');
  const count = calls.length;
  await assert.rejects(controller.mutate('cutover',{sourcePollerUid},true));
  assert.equal(calls.length,count);
});

test('GET alone cannot enable requests even if aggregate status claims MATCH and readiness', async () => {
  const f = fixture();
  f.readback({...report,validation:'MATCH',cutoverReady:true,observation:comparison});
  await f.controller.refresh();
  assert.equal(f.controller.state.comparison,null);
  await assert.rejects(request(f));
});
