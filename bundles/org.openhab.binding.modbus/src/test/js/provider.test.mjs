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

const candidate = {
  sourcePollerUid:'source',targetPollerUid:'target',revisionDigest:'candidate-digest',candidateYaml:'yaml',instructions:'manual',
  sources:[{sourceUid:'data',providerIdentity:'YamlProvider',sourceLocation:'UNKNOWN_MANUAL_LOCATION',revisionDigest:'source-digest'}],
  linkPatches:[{itemName:'Item',sourceChannelUid:'source:number',targetChannelUid:'target:number',providerIdentity:'YamlLinkProvider',
    sourceLocation:'UNKNOWN_MANUAL_LOCATION',revisionDigest:'link-digest',profileConfiguration:{profile:'system:default'}}]
};
const manual = {sourceUid:'manual-source',reason:'Manual review',configurationIdentity:'manual-digest'};

function malformedRecords(record) {
  const records = [null, [], 'private detail', 42, true, {}];
  for (const key of Object.keys(record)) {
    const missing = {...record}; delete missing[key]; records.push(missing);
    const values = typeof record[key] === 'string' ? [null, [], {}, 42, true] : [null, [], 'private detail', 42, true];
    for (const value of values) records.push({...record,[key]:value});
  }
  return records;
}

test('every nested provider record is validated before publication without damaging managed review', async () => {
  const invalid = [];
  for (const source of malformedRecords(candidate.sources[0])) invalid.push({...proposal,fileChanges:[{...candidate,sources:[candidate.sources[0],source]}]});
  for (const patch of malformedRecords(candidate.linkPatches[0])) invalid.push({...proposal,fileChanges:[{...candidate,linkPatches:[candidate.linkPatches[0],patch]}]});
  for (const work of malformedRecords(manual)) invalid.push({...proposal,manualWork:[manual,work]});
  for (const key of ['sources','linkPatches']) {
    for (const value of [undefined,null,{},'private detail',42,true]) invalid.push({...proposal,fileChanges:[{...candidate,[key]:value}]});
  }
  for (const key of ['fileChanges','manualWork']) {
    for (const value of [undefined,null,{},'private detail',42,true]) invalid.push({...proposal,[key]:value});
  }
  for (const body of invalid) {
    const calls = [];
    const report = {preview:{identity:'managed'},status:'PREVIEW'};
    const controller = createController({auth:{headers:async () => ({}),invalidate() {}},fetch:async (url,init) => {
      calls.push({url,init});
      return new Response(JSON.stringify(url.endsWith('provider-previews') ? body : report));
    }});
    Object.assign(controller.state,{identity:'managed',report,manualWork:[manual]});
    await assert.rejects(controller.providerPreviews(), /Invalid backend response/);
    assert.equal(controller.state.providerPreview,null);
    assert.equal(controller.state.providerError,'Invalid backend response.');
    assert.equal(controller.state.report,report);
    assert.deepEqual(controller.state.manualWork,[manual]);
    assert.equal(controller.state.uncertain,false);
    await controller.mutate('apply',{},true);
    assert.equal(controller.state.busy,false);
    assert.equal(calls.filter(c => c.init.method === 'POST').length,1);
  }
});

test('provider validation matches Java fields and renderer aliases without narrowing profile maps', async () => {
  const aliasCandidate = structuredClone(candidate);
  const source = aliasCandidate.sources[0];
  source.configurationDigest = source.revisionDigest; delete source.revisionDigest;
  const patch = aliasCandidate.linkPatches[0];
  for (const [alias,old] of [['sourceLinkProviderIdentity','providerIdentity'],['sourceLinkDigest','revisionDigest'],['sourceProfileConfiguration','profileConfiguration']]) {
    patch[alias] = patch[old]; delete patch[old];
  }
  const valid = [proposal,{...proposal,manualWork:[{...manual,configurationIdentity:''}]},
    {...proposal,fileChanges:[{...candidate,sources:[],linkPatches:[]}]},
    ...[candidate,aliasCandidate].flatMap(change => [{}, {profile:'system:default'},
      {profile:'custom',nested:{values:['private detail',null,{secret:'private detail'}]}}].map(profile => ({...proposal,
        fileChanges:[{...change,linkPatches:[{...change.linkPatches[0],
          ...(change === candidate ? {profileConfiguration:profile} : {sourceProfileConfiguration:profile})}]}]})))];
  for (const body of valid) {
    const controller = createController({auth:{headers:async () => ({}),invalidate() {}},
      fetch:async () => new Response(JSON.stringify(body))});
    assert.deepEqual(await controller.providerPreviews(),body);
    assert.equal(controller.state.providerError,'');
  }
  const invalid = [];
  for (const key of ['configurationDigest']) for (const value of [[],{},42,true]) {
    invalid.push({...proposal,fileChanges:[{...candidate,sources:[{...candidate.sources[0],[key]:value}]}]});
  }
  for (const key of ['sourceLinkProviderIdentity','sourceLinkDigest','sourceProfileConfiguration']) {
    for (const value of [[],42,true,...(key === 'sourceProfileConfiguration' ? ['private detail'] : [{}])]) {
      invalid.push({...proposal,fileChanges:[{...candidate,linkPatches:[{...candidate.linkPatches[0],[key]:value}]}]});
    }
  }
  for (const body of invalid) {
    const controller = createController({auth:{headers:async () => ({}),invalidate() {}},
      fetch:async () => new Response(JSON.stringify(body))});
    await assert.rejects(controller.providerPreviews(),/Invalid backend response/);
    assert.equal(controller.state.providerPreview,null);
  }
});

test('late malformed provider JSON cannot overwrite a replacement session review or error', async () => {
  for (const replacement of [null,'replacement']) {
    let session = 'first';
    const decoding = deferred(); const started = deferred();
    let first = true;
    const controller = createController({auth:{sessionVersion:() => session,headers:async () => ({}),invalidate() {}},fetch:async () => {
      if (!first) return new Response(JSON.stringify(proposal));
      first = false;
      return {ok:true,status:200,json:() => { started.resolve(); return decoding.promise; }};
    }});
    const pending = controller.providerPreviews();
    await started.promise;
    session = replacement; controller.invalidateSession();
    if (replacement) await controller.providerPreviews();
    decoding.resolve({...proposal,fileChanges:[{...candidate,sources:[null]}]});
    await assert.rejects(pending,/session changed/);
    assert.deepEqual(controller.state.providerPreview,replacement ? proposal : null);
    assert.equal(controller.state.providerError,'');
    assert.equal(controller.state.busy,false);
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
