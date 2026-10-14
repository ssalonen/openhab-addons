import test from 'node:test';
import assert from 'node:assert/strict';
import { createController } from '../../main/resources/migration/controller.mjs';

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return {promise, resolve};
}
const proposal = {revisionDigest:'revision',fileChanges:[],manualWork:[]};

test('provider failure clears stale proposals but leaves managed operations available', async () => {
  for (const failure of [401,403,503,'network','json','shape']) {
    const calls = [];
    let failing = false;
    const controller = createController({auth:{headers:async () => ({}),invalidate() {}},fetch:async (url, init) => {
      calls.push({url,init});
      if (url.endsWith('provider-previews')) {
        if (!failing) return new Response(JSON.stringify(proposal));
        if (failure === 'network') throw new Error('private detail');
        if (failure === 'json') return new Response('invalid');
        if (failure === 'shape') return new Response('{}');
        return new Response('{}',{status:failure});
      }
      return new Response(JSON.stringify({preview:{identity:'managed'},status:'PREVIEW'}));
    }});
    controller.state.identity = 'managed';
    await controller.refresh();
    await controller.providerPreviews();
    failing = true;
    await assert.rejects(controller.providerPreviews());
    assert.equal(controller.state.providerPreview, null);
    assert.match(controller.state.providerError, failure === 'network' ? /Network request failed/ : failure === 'shape' || failure === 'json' ? /Invalid backend response/ : failure === 401 ? /Session expired/ : failure === 403 ? /administrator/ : /503/);
    assert.ok(!controller.state.providerError.includes('private detail'));
    await controller.mutate('apply', {}, true);
    assert.equal(calls.filter(c => c.init.method === 'POST').length, 1);
  }
});

test('malformed provider candidate records fail before publishing review data', async () => {
  for (const change of [null,{}, {sourcePollerUid:'source',targetPollerUid:'target',revisionDigest:'digest',candidateYaml:42,sources:[],linkPatches:[],instructions:'manual'}]) {
    const controller = createController({auth:{headers:async () => ({}),invalidate() {}},
      fetch:async () => new Response(JSON.stringify({...proposal,fileChanges:[change]}))});
    await assert.rejects(controller.providerPreviews(), /Invalid backend response/);
    assert.equal(controller.state.providerPreview,null);
  }
});

test('session invalidation clears loaded provider data and discards late JSON', async () => {
  let session = 'first';
  let response = new Response(JSON.stringify(proposal));
  const controller = createController({auth:{sessionVersion:() => session, headers:async () => ({}), invalidate() {}},fetch:async () => response});
  await controller.providerPreviews();
  controller.state.providerError = 'Old session error';
  controller.invalidateSession();
  assert.equal(controller.state.providerPreview, null);
  assert.equal(controller.state.providerError, '');
  const decoding = deferred();
  const started = deferred();
  response = {ok:true,status:200,json:() => { started.resolve(); return decoding.promise; }};
  const pending = controller.providerPreviews();
  await started.promise;
  session = 'replacement';
  controller.invalidateSession();
  decoding.resolve(proposal);
  await assert.rejects(pending, /session changed/);
  assert.equal(controller.state.providerPreview, null);
});
