import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const base = new URL('../../main/resources/migration/', import.meta.url);
// Proposed DTO fixtures, not captured REST output: Java exposure is a separate acceptance gate.
const malicious = '<img src=x onerror=alert(1)>';
const comparisons = ['MATCH','MISMATCH','UNAVAILABLE'].map((outcome, index) => ({
  sourceChannelUid:`legacy:${index}:number`, targetChannelUid:`modbus:poller2:plant:channel${index}`,
  address:`holding:${100 + index}.1`, valueType:'int16', sourceValue:index === 2 ? 'UNAVAILABLE' : '42',
  targetValue:index === 2 ? 'UNAVAILABLE' : String(42 + index),
  sourceObservedAt:index === 2 ? '1970-01-01T00:00:00Z' : '2026-10-14T09:00:00Z',
  targetObservedAt:index === 2 ? '1970-01-01T00:00:00Z' : '2026-10-14T09:00:01Z',
  outcome, reason:`${outcome} reason ${malicious}`
}));
const report = {preview:{identity:'review',groups:[{sourcePollerUid:'legacy:poller',targetPollerUid:'modbus:poller2:plant',targetBridgeUid:'modbus:tcp:plant'},
  {sourcePollerUid:'legacy:other',targetPollerUid:'modbus:poller2:other',targetBridgeUid:'modbus:tcp:plant'}],impacts:[],manualWork:[]},
  status:'STAGED',stagedAt:'1970-01-01T00:00:00Z',validation:'UNAVAILABLE',cutoverReady:false,readinessReason:'Missing evidence'};
const observation = {previewIdentity:'review',sourcePollerUid:'legacy:poller',observedAt:'2026-10-14T09:00:02Z',outcome:'UNAVAILABLE',comparisons};
class Element {
  constructor(tag) { this.tag = tag; this.value = ''; this.handlers = {}; this.children = []; }
  addEventListener(name, fn) { this.handlers[name] = fn; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  set innerHTML(value) { throw new Error('Unsafe HTML rendering'); }
}
function descendants(e) { return [e,...e.children.flatMap(descendants)]; }
function text(e) { return descendants(e).map(e => e.textContent || '').join('\n'); }
async function fixture(name, validate = () => new Response(JSON.stringify({...report,observation}))) {
  const ids = new Map(); const events = {}; const calls = []; let session = 'refresh'; let intercept;
  globalThis.document = {cookie:'',getElementById:id => { if (!ids.has(id)) ids.set(id,new Element()); return ids.get(id); },createElement:tag => new Element(tag)};
  globalThis.window = {addEventListener:(name,fn) => { events[name] = fn; }};
  globalThis.location = {origin:'https://openhab.test'};
  globalThis.localStorage = {getItem:() => session};
  globalThis.confirm = () => true;
  globalThis.fetch = async (url, init) => {
    calls.push({url,init});
    const intercepted = intercept?.(url);
    if (intercepted) return intercepted;
    if (url === '/rest/auth/token') return new Response(JSON.stringify({access_token:'access',expires_in:60}));
    if (url.endsWith('/validate')) return validate();
    return new Response(JSON.stringify(report));
  };
  await import(new URL(`app.mjs?comparisons-${name}`,base));
  ids.get('identity').value = 'review';
  await ids.get('refresh').handlers.click();
  return {ids,calls,intercept:fn => { intercept = fn; },logout:() => { session = null; events.storage({key:'openhab.ui:refreshToken'}); },validate:() => descendants(ids.get('groups')).find(e => e.textContent === 'Validate this group').handlers.click()};
}
test('fixture DTO comparisons survive validate readback and render every field safely', async () => {
  const f = await fixture('details');
  await f.validate();
  assert.ok(f.ids.has('comparisons'), 'detailed comparison region must be rendered');
  const rendered = text(f.ids.get('comparisons'));
  for (const comparison of comparisons) for (const value of Object.values(comparison))
    if (!String(value).startsWith('1970-01-01')) assert.ok(rendered.includes(value),value);
  assert.match(rendered,/Thing ID: modbus:poller2:plant/);
  assert.match(rendered,/2026-10-14T09:00:02Z/);
  assert.ok(!/lifecycle|generation/i.test(rendered));
  const cutover = descendants(f.ids.get('groups')).find(e => e.textContent === 'Request cutover for this group');
  assert.equal(cutover.disabled,true);
  assert.equal(f.calls.filter(c => c.url !== '/rest/auth/token' && c.init?.method === 'POST').length,1);
  assert.equal(f.calls.at(-1).init.method,'GET');
  await f.ids.get('refresh').handlers.click();
  assert.ok(!text(f.ids.get('comparisons')).includes(malicious),'explicit GET must not present old evidence as fresh');
  assert.match(text(f.ids.get('comparisons')),/No detailed comparison/);
});

test('absent evidence timestamps are not rendered as epoch device readings', async () => {
  const f = await fixture('no-evidence', () => new Response(JSON.stringify({...report,
    observation:{...observation,observedAt:'1970-01-01T00:00:00Z',comparisons:[comparisons[2]]}})));
  await f.validate();
  assert.doesNotMatch(text(f.ids.get('comparisons')),/1970/);
  assert.match(text(f.ids.get('comparisons')),/Not observed/);
  assert.doesNotMatch(text(f.ids.get('summary')),/1970/);
});

test('selected MATCH enables only a cutover request and separates generic GET summary from explicit comparison', async () => {
  const f = await fixture('selected-match', () => new Response(JSON.stringify({...report,
    observation:{...observation,outcome:'MATCH',comparisons:[comparisons[0]]}})));
  await f.validate();
  const cutover = descendants(f.ids.get('groups')).find(e => e.textContent === 'Request cutover for this group');
  assert.ok(cutover, 'label must describe a request, not retained server authorization');
  assert.equal(cutover.disabled,false);
  assert.equal(descendants(f.ids.get('groups')).filter(e => e.textContent === 'Request cutover for this group')[1].disabled,true);
  assert.match(text(f.ids.get('summary')),/Read-only report validation/);
  assert.match(text(f.ids.get('summary')),/UNAVAILABLE/);
  assert.match(text(f.ids.get('comparisons')),/Last explicit selected-group comparison: MATCH/);
  assert.match(text(f.ids.get('readiness')),/new fresh reads.*refuse/i);
  await cutover.handlers.click();
  assert.equal(f.calls.filter(c => c.url.endsWith('/cutover')).length,1);
  assert.equal(descendants(f.ids.get('groups')).find(e => e.textContent === 'Request cutover for this group').disabled,true);
});

test('editing identity immediately clears comparison and disables every group request', async () => {
  const f = await fixture('identity-edit', () => new Response(JSON.stringify({...report,
    observation:{...observation,outcome:'MATCH',comparisons:[comparisons[0]]}})));
  await f.validate();
  const input = f.ids.get('identity');
  input.value = 'different-review';
  assert.equal(typeof input.handlers.input,'function','identity edits must invalidate the selected comparison');
  input.handlers.input();
  assert.equal(input.value,'different-review');
  assert.match(text(f.ids.get('comparisons')),/No detailed comparison/);
  for (const button of descendants(f.ids.get('groups')).filter(e => e.textContent === 'Request cutover for this group'))
    assert.equal(button.disabled,true);
});

test('logout clears comparison fixtures and discards delayed validation or readback without replay', async () => {
  for (const phase of ['validate','readback']) {
    const f = await fixture(`logout-${phase}`);
    await f.validate();
    assert.ok(text(f.ids.get('comparisons')).includes(malicious));
    let release;
    let entered;
    const waiting = new Promise(resolve => { entered = resolve; });
    f.intercept(url => {
      if ((phase === 'validate' && url.endsWith('/validate'))
          || (phase === 'readback' && url.endsWith('/review'))) {
        entered();
        return new Promise(resolve => { release = () => resolve(new Response(JSON.stringify(url.endsWith('/validate') ? {...report,observation} : report))); });
      }
    });
    const old = f.validate();
    await waiting;
    const count = f.calls.length;
    f.logout();
    assert.ok(!text(f.ids.get('comparisons')).includes(malicious));
    release();
    await old;
    assert.ok(!text(f.ids.get('comparisons')).includes(malicious));
    assert.equal(f.calls.length,count,'no stale follow-up GET or POST replay');
    assert.equal(f.ids.get('stage').disabled,true);
  }
});

test('comparison section explains read-only evidence and has an accessible heading', async () => {
  const html = await readFile(new URL('index.html',base),'utf8');
  assert.match(html,/id="comparisons"/);
  assert.match(html,/aria-labelledby="comparison-title"/);
  assert.match(html,/read-equivalence/);
  assert.match(html,/not.*device writes/i);
});
