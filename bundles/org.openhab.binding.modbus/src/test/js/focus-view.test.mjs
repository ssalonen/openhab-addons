import test from 'node:test';
import assert from 'node:assert/strict';
const base = new URL('../../main/resources/migration/', import.meta.url);
let fixtureId = 0;

async function fixture() {
  const ids = new Map(); const events = {}; const calls = []; const pending = [];
  let session = 'first'; let delay = false; let failure = false; let status = 'PREVIEW';
  const document = {cookie:'',activeElement:null};
  class Element {
    constructor(tag = 'button') { this.tag = tag; this.value = ''; this.handlers = {}; this.children = []; }
    addEventListener(name, fn) { this.handlers[name] = fn; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) {
      const contains = element => element === document.activeElement || element.children.some(contains);
      if (this.children.some(contains)) document.activeElement = document.body;
      this.children = children;
    }
    set disabled(value) {
      this.unavailable = value;
      // Model the browser's loss of keyboard focus when a focused button is disabled.
      if (value && document.activeElement === this) document.activeElement = document.body;
    }
    get disabled() { return this.unavailable; }
    focus() {
      if (!this.disabled) {
        document.activeElement = this;
        events.focusin?.({target:this});
      }
    }
  }
  document.body = new Element('body'); document.activeElement = document.body;
  document.getElementById = id => {
    if (!ids.has(id)) ids.set(id,new Element());
    return ids.get(id);
  };
  document.createElement = tag => new Element(tag);
  globalThis.document = document;
  globalThis.window = {addEventListener:(name, fn) => { events[name] = fn; }};
  globalThis.location = {origin:'https://openhab.test'};
  globalThis.localStorage = {getItem:() => session};
  globalThis.confirm = () => true;
  const plan = {identity:'review',groups:[{sourcePollerUid:'source',targetPollerUid:'target',targetBridgeUid:'bridge'}],impacts:[],manualWork:[],yaml:'things:'};
  function response(url, init) {
    if (failure) return new Response('{}',{status:503});
    if (url.endsWith('/previews')) return new Response(JSON.stringify({plan,manualWork:[]}));
    if (url.endsWith('/provider-previews')) return new Response(JSON.stringify({revisionDigest:'digest',fileChanges:[],manualWork:[]}));
    if (init.method === 'POST') status = url.endsWith('/rollback') ? 'ROLLED_BACK' : 'STAGED';
    return new Response(JSON.stringify({preview:plan,status}));
  }
  globalThis.fetch = async (url, init) => {
    if (url === '/rest/auth/token') return new Response(JSON.stringify({access_token:'access',expires_in:60}));
    calls.push({url,init});
    if (delay) {
      delay = false;
      return new Promise(resolve => pending.push(() => resolve(response(url,init))));
    }
    return response(url,init);
  };
  await import(new URL(`app.mjs?static-focus-${fixtureId++}`,base));
  ids.get('identity').value = 'review';
  return {ids,document,calls,pending,
    fail:() => { failure = true; }, delay:() => { delay = true; },
    session(value, notify = true) { session = value; if (notify) events.storage({key:'openhab.ui:refreshToken'}); },
    async prepare(control) {
      if (['stage','rollback','validate'].includes(control)) await ids.get('refresh').handlers.click();
      if (['rollback','validate'].includes(control)) await ids.get('stage').handlers.click();
    }
  };
}

for (const control of ['refresh','preview','provider-refresh','stage','rollback']) {
  for (const outcome of ['success','failure']) {
    test(`keyboard ${control} restores ${outcome} focus after temporary disabling`, async () => {
      const f = await fixture(); await f.prepare(control);
      const button = f.ids.get(control);
      assert.equal(button.disabled,false);
      if (outcome === 'failure') f.fail();
      button.focus();
      const operation = button.handlers.click();
      assert.equal(f.document.activeElement,f.document.body,'busy rendering blurs the disabled button');
      await operation;
      const target = button.disabled ? f.ids.get('status') : button;
      assert.equal(f.document.activeElement,target,'restore enabled action or focus the result if consumed/unavailable');
      assert.equal(f.ids.get('preview').disabled,false);
      if (outcome === 'failure') assert.match(f.ids.get('status').textContent,/Operation stopped/);
      if (outcome === 'success' && ['stage','rollback'].includes(control)) {
        assert.equal(button.disabled,true,'successful mutation consumes this static action');
        assert.equal(f.document.activeElement,f.ids.get('status'));
      }
    });
  }
}

for (const control of ['refresh','validate']) for (const value of [null,'replacement'])
for (const notify of [true,false]) for (const move of [false,true]) {
  test(`late ${control} does not reclaim focus after ${value === null ? 'logout' : 'replacement'} (${notify ? 'storage event' : 'same tab'}, moved: ${move})`, async () => {
    const f = await fixture(); await f.prepare(control); f.delay();
    const button = action(f,control); button.focus();
    const operation = button.handlers.click();
    for (let i = 0; i < 40 && !f.pending.length; i++) await Promise.resolve();
    assert.equal(f.pending.length,1);
    f.session(value,notify);
    if (move) f.ids.get('identity').focus();
    const target = f.document.activeElement;
    const requestCount = f.calls.length;
    f.pending[0](); await operation;
    assert.equal(f.document.activeElement,target,'stale completion must not focus either action or status');
    assert.match(f.ids.get('status').textContent,/session changed/);
    assert.equal(f.ids.get('summary').children.length,0);
    assert.equal(f.calls.length,requestCount,'no stale follow-up request');
    assert.equal(f.calls.at(-1).url,control === 'refresh' ? '/rest/modbus/migration/workflows/review'
      : '/rest/modbus/migration/workflows/review/validate','no stale follow-up request');
  });
}

function action(f, control) {
  return control === 'validate' ? f.ids.get('groups').children[0]?.children[2].children[0] : f.ids.get(control);
}

for (const control of ['refresh','stage','validate']) for (const outcome of ['success','failure'])
for (const move of ['none','Main UI link','Main UI link then body']) {
  test(`pending ${control} ${outcome} respects focus move: ${move}`, async () => {
    const f = await fixture(); await f.prepare(control);
    const button = action(f,control); button.focus(); f.delay();
    if (outcome === 'failure') f.fail();
    const operation = button.handlers.click();
    for (let i = 0; i < 40 && !f.pending.length; i++) await Promise.resolve();
    assert.equal(f.pending.length,1);
    assert.equal(f.document.activeElement,f.document.body);
    if (move !== 'none') {
      f.document.getElementById('main-ui-link').focus();
      if (move.endsWith('then body')) f.document.body.focus();
    }
    const before = f.document.activeElement;
    f.pending[0](); await operation;
    if (move !== 'none') assert.equal(f.document.activeElement,before,'completion must respect intentional focus movement');
    else {
      const replacement = action(f,control);
      assert.equal(f.document.activeElement,!replacement || replacement.disabled ? f.ids.get('status') : replacement);
    }
  });
}

test('unfocused static invocation does not introduce a focus jump', async () => {
  const f = await fixture();
  await f.ids.get('refresh').handlers.click();
  assert.equal(f.document.activeElement,f.document.body);
});
