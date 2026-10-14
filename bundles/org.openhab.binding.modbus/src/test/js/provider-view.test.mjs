import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const base = new URL('../../main/resources/migration/', import.meta.url);
const malicious = '</pre><img src=x onerror=alert(1)>';
const proposal = {revisionDigest:'review-digest',fileChanges:[{
  sourcePollerUid:malicious,targetPollerUid:'modbus:poller2:target',revisionDigest:'group-digest',
  sources:[{sourceUid:malicious,providerIdentity:'YamlProvider',sourceLocation:'UNKNOWN_MANUAL_LOCATION',revisionDigest:'config-digest'}],
  candidateYaml:malicious, instructions:malicious,
  linkPatches:[{itemName:malicious,sourceChannelUid:'source:number',targetChannelUid:'target:number',providerIdentity:'YamlLinkProvider',sourceLocation:'UNKNOWN_MANUAL_LOCATION',revisionDigest:'link-digest',profileConfiguration:{profile:'system:default'}}]
}],manualWork:[{sourceUid:malicious,reason:'Transformation omitted',configurationIdentity:'manual-digest'}]};
class Element {
  constructor(tag) { this.tag = tag; this.value = ''; this.handlers = {}; this.children = []; }
  addEventListener(name, fn) { this.handlers[name] = fn; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  set innerHTML(value) { throw new Error('Unsafe HTML rendering'); }
  click() { this.clicked = true; }
}
function descendants(element) { return [element,...element.children.flatMap(descendants)]; }

test('manual-only provider review explains omitted conversions without offering YAML exports', async () => {
  const f = await fixture('manual-only',{revisionDigest:'manual-review',fileChanges:[],manualWork:[{sourceUid:'custom-profile-source',reason:'Custom profile omitted; edit manually',configurationIdentity:'digest-only'}]});
  await f.ids.get('provider-refresh').handlers.click();
  const root = f.ids.get('provider-proposals');
  assert.match(text(root),/No eligible candidate YAML/);
  assert.match(text(root),/Custom profile omitted; edit manually/);
  assert.match(text(root),/digest-only/);
  assert.equal(descendants(root).filter(e => e.tag === 'button' || e.tag === 'pre').length,0);
});

test('provider profile rendering omits non-default configuration wholesale', async () => {
  const changed = structuredClone(proposal);
  changed.fileChanges[0].sources[0].configurationDigest = 'new-config-digest';
  changed.fileChanges[0].linkPatches[0] = {...changed.fileChanges[0].linkPatches[0],
    sourceLinkDigest:'new-link-digest',sourceLinkProviderIdentity:'NewProvider',
    sourceProfileConfiguration:{profile:'custom',arbitrary:'secret-profile-value'}};
  const f = await fixture('omission',changed);
  await f.ids.get('provider-refresh').handlers.click();
  const rendered = text(f.ids.get('provider-proposals'));
  for (const value of ['new-config-digest','new-link-digest','NewProvider','profile configuration omitted']) assert.ok(rendered.includes(value),value);
  assert.ok(!rendered.includes('secret-profile-value'));
});
test('profile aliases preserve explicit safe maps and omit nested custom values', async () => {
  const profiles = [
    {value:{},expected:'Source profile: {}'},
    {value:{profile:'system:default'},expected:'Source profile: {"profile":"system:default"}'},
    ...[{profile:{secret:'private detail'}},{profile:['private detail']},{profile:null},
      {profile:'custom',nested:{values:[null,'private detail']}}].map(value => ({value,expected:'profile configuration omitted'}))
  ];
  let index = 0;
  for (const {value,expected} of profiles) {
    const body = structuredClone(proposal);
    const patch = body.fileChanges[0].linkPatches[0];
    patch.sourceProfileConfiguration = value;
    patch.profileConfiguration = {profile:'custom',secret:'private detail'};
    const f = await fixture(`profile-map-${index++}`,body);
    await f.ids.get('provider-refresh').handlers.click();
    const rendered = text(f.ids.get('provider-proposals'));
    assert.ok(rendered.includes(expected));
    assert.ok(!rendered.includes('private detail'));
    assert.equal(f.ids.get('provider-refresh').disabled,false);
    assert.equal(f.calls.filter(c => c.url !== '/rest/auth/token').length,1);
  }
});
function text(element) { return descendants(element).map(e => e.textContent || '').join('\n'); }
test('malformed nested proposals cannot break rendering or disable the next managed capture', async () => {
  const malformed = [];
  for (const field of ['sources','linkPatches']) {
    for (const entry of [null,{},[],42,'private detail']) {
      const body = structuredClone(proposal);
      body.fileChanges[0][field].push(entry);
      malformed.push(body);
    }
  }
  for (const entry of [null,{},[],42,'private detail']) malformed.push({...proposal,manualWork:[entry]});
  let index = 0;
  for (const body of malformed) {
    const f = await fixture(`nested-${index++}`,body,true);
    await assert.doesNotReject(f.ids.get('provider-refresh').handlers.click());
    assert.equal(f.ids.get('provider-proposals').children.length,0);
    assert.equal(f.ids.get('provider-status').textContent,'Provider proposals unavailable: Invalid backend response.');
    assert.equal(f.ids.get('preview').disabled,false);
    await assert.doesNotReject(f.ids.get('preview').handlers.click());
    assert.equal(f.ids.get('preview').disabled,false);
    assert.equal(f.ids.get('stage').disabled,false);
    assert.equal(f.ids.get('identity').value,'managed');
    const posts = f.calls.filter(c => c.url !== '/rest/auth/token' && c.init.method === 'POST');
    assert.equal(posts.length,1);
    assert.equal(posts[0].url,'/rest/modbus/migration/previews');
    assert.ok(!text(f.ids.get('provider-proposals')).includes('private detail'));
  }
});

async function fixture(name, result = proposal, managed = false) {
  const ids = new Map(); const events = {}; const calls = []; let session = 'refresh';
  globalThis.document = {cookie:'',getElementById:id => { if (!ids.has(id)) ids.set(id,new Element()); return ids.get(id); },createElement:tag => new Element(tag)};
  globalThis.window = {addEventListener:(name,fn) => { events[name] = fn; }};
  globalThis.location = {origin:'https://openhab.test'};
  globalThis.localStorage = {getItem:() => session};
  globalThis.confirm = () => { throw new Error('File proposals must not mutate'); };
  globalThis.fetch = async (url,init) => {
    calls.push({url,init});
    if (url === '/rest/auth/token') return new Response(JSON.stringify({access_token:'access',expires_in:60}));
    if (url.endsWith('provider-previews')) return typeof result === 'function' ? result() : new Response(JSON.stringify(result));
    if (managed && url.endsWith('/previews')) return new Response(JSON.stringify({plan:{identity:'managed'},manualWork:[]}));
    if (managed && url.endsWith('/workflows/managed')) return new Response(JSON.stringify({preview:{identity:'managed',groups:[],impacts:[]},status:'PREVIEW'}));
    throw new Error('Unexpected managed mutation');
  };
  await import(new URL(`app.mjs?provider-${name}`,base));
  return {ids,calls,events,change:value => { session = value; },logout:() => { session = null; events.storage({key:'openhab.ui:refreshToken'}); }};
}

test('hosted provider review displays escaped detached candidates without managed actions', async () => {
  const html = await readFile(new URL('index.html',base),'utf8');
  assert.ok(html.includes('id="provider-refresh"'));
  assert.match(html,/Read-only.*manual/);
  const f = await fixture('display');
  await f.ids.get('provider-refresh').handlers.click();
  const root = f.ids.get('provider-proposals');
  const rendered = text(root);
  for (const value of [malicious,'YamlProvider','UNKNOWN_MANUAL_LOCATION','config-digest','link-digest','system:default','source:number','target:number','review-digest','Transformation omitted','manual-digest']) assert.ok(rendered.includes(value),value);
  assert.equal(descendants(root).filter(e => e.tag === 'pre')[0].textContent,malicious);
  assert.deepEqual(descendants(root).filter(e => e.tag === 'button').map(e => e.textContent),['Copy candidate YAML','Download candidate YAML']);
  assert.equal(f.ids.get('stage').disabled,true);
  assert.equal(f.calls.filter(c => c.url !== '/rest/auth/token').length,1);
  assert.equal(f.calls.at(-1).init.method,'GET');
  f.logout();
  assert.ok(!text(root).includes(malicious));
});

test('same-tab token replacement prevents exporting a previous session candidate', async () => {
  const f = await fixture('export-session');
  let copied = false;
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async () => { copied = true; }}}});
  await f.ids.get('provider-refresh').handlers.click();
  const copy = descendants(f.ids.get('provider-proposals')).find(e => e.textContent === 'Copy candidate YAML');
  f.change('replacement');
  await copy.handlers.click();
  assert.equal(copied,false);
  assert.ok(!text(f.ids.get('provider-proposals')).includes(malicious));
});

test('late provider responses cannot restore proposals or unlock a replacement session operation', async () => {
  for (const status of [200,401,503]) {
    const pending = [];
    const f = await fixture(`late-${status}`,() => new Promise(resolve => pending.push(resolve)));
    const old = f.ids.get('provider-refresh').handlers.click();
    for (let i = 0; i < 40 && pending.length < 1; i++) await Promise.resolve();
    assert.equal(pending.length,1);
    f.logout();
    f.change('replacement'); f.events.storage({key:'openhab.ui:refreshToken'});
    const newer = f.ids.get('provider-refresh').handlers.click();
    for (let i = 0; i < 40 && pending.length < 2; i++) await Promise.resolve();
    assert.equal(pending.length,2);
    pending[0](new Response(JSON.stringify(proposal),{status}));
    await old;
    assert.equal(f.ids.get('provider-refresh').disabled,true);
    assert.ok(!text(f.ids.get('provider-proposals')).includes(malicious));
    f.logout();
    pending[1](new Response(JSON.stringify(proposal)));
    await newer;
    assert.equal(f.ids.get('provider-refresh').disabled,false);
    assert.equal(f.ids.get('provider-proposals').children.length,0);
    assert.equal(f.calls.filter(c => c.url !== '/rest/auth/token').length,2);
  }
});

test('provider load failures are displayed as unavailable rather than empty success', async () => {
  const f = await fixture('failure',() => new Response('{}',{status:503}));
  await f.ids.get('provider-refresh').handlers.click();
  assert.match(f.ids.get('provider-status').textContent,/unavailable:.*503/);
  assert.equal(f.ids.get('provider-proposals').children.length,0);
  assert.equal(f.ids.get('preview').disabled,false);
});

test('candidate copy and download export only reviewed YAML bytes without backend writes', async () => {
  const f = await fixture('export');
  let copied;
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async value => { copied = value; }}}});
  let blob; let revoked;
  const create = URL.createObjectURL; const revoke = URL.revokeObjectURL;
  URL.createObjectURL = value => { blob = value; return 'blob:review'; };
  URL.revokeObjectURL = value => { revoked = value; };
  try {
    await f.ids.get('provider-refresh').handlers.click();
    assert.match(f.ids.get('status').textContent,/Provider proposals refetched/);
    const buttons = descendants(f.ids.get('provider-proposals')).filter(e => e.tag === 'button');
    await buttons[0].handlers.click();
    assert.equal(copied,malicious);
    await buttons[1].handlers.click();
    assert.equal(await blob.text(),malicious);
    assert.equal(blob.type,'application/yaml;charset=utf-8');
    assert.equal(revoked,'blob:review');
    assert.equal(f.calls.filter(c => c.url !== '/rest/auth/token').length,1);
  } finally { URL.createObjectURL = create; URL.revokeObjectURL = revoke; }
});
