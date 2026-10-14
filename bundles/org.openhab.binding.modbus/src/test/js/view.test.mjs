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
