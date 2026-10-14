import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const base = new URL('../../main/resources/migration/', import.meta.url);
test('load uses the identity typed by the user rather than the previous review', async () => {
  class Element {
    constructor() { this.value = ''; this.handlers = {}; this.children = []; }
    addEventListener(name, fn) { this.handlers[name] = fn; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
  }
  const ids = new Map();
  globalThis.document = { cookie:'', getElementById: id => { if (!ids.has(id)) ids.set(id,new Element()); return ids.get(id); }, createElement: () => new Element() };
  globalThis.window = { addEventListener() {} };
  globalThis.location = { origin:'https://openhab.test' };
  globalThis.localStorage = {getItem: () => 'refresh'};
  globalThis.confirm = () => false;
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(url);
    if (url === '/rest/auth/token') return new Response(JSON.stringify({access_token:'access',expires_in:60}));
    if (url.endsWith('previews')) return new Response(JSON.stringify({plan:{identity:'old'},manualWork:[]}));
    return new Response(JSON.stringify({preview:{identity:url.split('/').at(-1),groups:[],impacts:[],manualWork:[],yaml:'things:'},status:'PREVIEW',validation:'UNAVAILABLE',readinessReason:'No evidence'}));
  };
  await import(new URL('app.mjs?identity-test',base));
  await ids.get('preview').handlers.click();
  ids.get('identity').value = 'new-review';
  await ids.get('refresh').handlers.click();
  assert.equal(calls.at(-1), '/rest/modbus/migration/workflows/new-review');
  assert.equal(ids.get('identity').value, 'new-review');
});

test('storage logout clears controls and late requests cannot unlock a new session operation', async () => {
  for (const operation of ['refresh', 'preview', 'stage']) {
    class Element {
      constructor() { this.value = ''; this.handlers = {}; this.children = []; }
      addEventListener(name, fn) { this.handlers[name] = fn; }
      append(...children) { this.children.push(...children); }
      replaceChildren(...children) { this.children = children; }
    }
    const ids = new Map();
    const handlers = {};
    let session = 'first';
    let delay = false;
    const pending = [];
    globalThis.document = {cookie:'', getElementById: id => { if (!ids.has(id)) ids.set(id,new Element()); return ids.get(id); }, createElement: () => new Element()};
    globalThis.window = {addEventListener: (name, fn) => { handlers[name] = fn; }};
    globalThis.location = {origin:'https://openhab.test'};
    globalThis.localStorage = {getItem: () => session};
    globalThis.confirm = () => true;
    const review = {preview:{identity:'review',groups:[],impacts:[],manualWork:[],yaml:'privileged yaml'},status:'PREVIEW'};
    const result = url => new Response(JSON.stringify(url.endsWith('previews') ? {plan:review.preview,manualWork:[{sourceUid:'private',reason:'secret'}]} : review));
    globalThis.fetch = async url => {
      if (url === '/rest/auth/token') return new Response(JSON.stringify({access_token:session,expires_in:60}));
      if (!delay) return result(url);
      return new Promise(resolve => pending.push({url, resolve}));
    };
    await import(new URL(`app.mjs?logout-${operation}`,base));
    await ids.get('preview').handlers.click();
    delay = true;
    const old = ids.get(operation).handlers.click();
    for (let i = 0; i < 30 && pending.length < 1; i++) await Promise.resolve();
    assert.equal(pending.length, 1);
    session = null; handlers.storage({key:'openhab.ui:refreshToken'});
    assert.equal(ids.get('stage').disabled, true);
    assert.equal(ids.get('summary').children.length, 0);
    assert.equal(ids.get('manual').children.length, 1);
    assert.equal(ids.get('preview').disabled, false);
    session = 'second'; handlers.storage({key:'openhab.ui:refreshToken'});
    const newer = ids.get('preview').handlers.click();
    for (let i = 0; i < 30 && pending.length < 2; i++) await Promise.resolve();
    assert.equal(pending.length, 2);
    pending[0].resolve(result(pending[0].url)); await old;
    assert.equal(ids.get('preview').disabled, true, 'old finally must not clear new busy state');
    assert.equal(ids.get('summary').children.length, 0);
    assert.equal(ids.get('stage').disabled, true);
    session = null; handlers.storage({key:null});
    pending[1].resolve(result(pending[1].url)); await newer;
    assert.equal(pending.length, 2, 'no stale follow-up GET or POST replay');
    assert.equal(ids.get('stage').disabled, true);
    assert.equal(ids.get('yaml').textContent, 'No reviewed plan loaded.');
    assert.match(ids.get('status').textContent, /session changed/);
  }
});

test('view provides labelled review, group and recovery controls without inline scripts', async () => {
  const html = await readFile(new URL('index.html', base), 'utf8');
  for (const id of ['preview','identity','refresh','stage','groups','impacts','yaml','manual','rollback','status','error'])
    assert.ok(html.includes(`id="${id}"`), id);
  assert.ok(html.includes('aria-live="polite"'));
  assert.ok(html.includes('<label for="identity"'));
  assert.ok(!/<script[^>]*>[^<]+<\/script>/.test(html));
});
test('staging consent discloses parallel polling without switching production links', async () => {
  const app = await readFile(new URL('app.mjs', base), 'utf8');
  const html = await readFile(new URL('index.html', base), 'utf8');
  assert.ok(app.includes('may poll alongside legacy'));
  assert.ok(app.includes('Item links do not switch'));
  assert.ok(!app.includes('Candidates are disabled'));
  assert.ok(html.includes('may poll alongside legacy'));
});

test('renderer uses safe text nodes and explicit confirmation for dangerous actions', async () => {
  const app = await readFile(new URL('app.mjs', base), 'utf8');
  assert.ok(!app.includes('innerHTML'));
  assert.ok(app.includes('textContent'));
  assert.ok(app.includes('confirm('));
  assert.ok(app.includes('cutoverBlockReason'));
});
