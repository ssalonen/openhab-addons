import test from 'node:test';
import assert from 'node:assert/strict';
import { createController } from '../../main/resources/migration/controller.mjs';
const plan = {identity:'review',yaml:'things:',groups:[{sourcePollerUid:'source',targetPollerUid:'target',targetBridgeUid:'bridge'}],impacts:[{itemName:'Item',sourceChannelUid:'source:number',targetChannelUid:'target:number',targetLinkPlanned:true,profileConfiguration:{profile:'custom',nested:{secret:'opaque'}}}],manualWork:[{sourceUid:'source',reason:'Manual review',configurationIdentity:''}]};
const report = {preview:plan,status:'STAGED',validation:'UNAVAILABLE',switchedLinks:[],stagedAt:null};
const malformed = [{}, {status:'STAGED'}, {...report,preview:null}];
for (const field of ['groups','impacts','manualWork']) {
  for (const entry of [null,{},[],42]) malformed.push({...report,preview:{...plan,[field]:[entry]}});
}
malformed.push({...report,switchedLinks:[null]}, {...report,stagedAt:{private:'secret'}});
for (const [index,bad] of malformed.entries()) test(`malformed workflow ${index} is sanitized, blocks mutation, and permits recovery`, async () => {
  let result = report; const calls = [];
  const controller = createController({auth:{headers:async () => ({})},fetch:async (url,init) => {
    calls.push({url,init}); return new Response(JSON.stringify(url.endsWith('/previews') ? {plan,manualWork:[]} : result));
  }});
  await controller.preview(); result = bad;
  await assert.rejects(controller.refresh(), {message:'Invalid backend response.'});
  assert.equal(controller.state.report,null);
  assert.equal(controller.state.comparison,null);
  assert.equal(controller.state.busy,false);
  const count = calls.length;
  await assert.rejects(controller.mutate('apply',{},true),/Refetch/);
  assert.equal(calls.length,count);
  result = report; await controller.preview();
  assert.deepEqual(controller.state.report,report);
});

test('malformed capture manual entries cannot poison later renders or retain an old workflow', async () => {
  let bad = false;
  const controller = createController({auth:{headers:async () => ({})},fetch:async url => new Response(JSON.stringify(url.endsWith('/previews') ? {plan,manualWork:bad ? [null] : []} : report))});
  await controller.preview(); bad = true;
  await assert.rejects(controller.preview(),{message:'Invalid backend response.'});
  assert.equal(controller.state.report,null);
  assert.deepEqual(controller.state.manualWork,[]);
  assert.equal(controller.state.busy,false);
  bad = false; await controller.preview();
  assert.deepEqual(controller.state.report,report);
});
