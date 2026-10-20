import test from 'node:test';
import assert from 'node:assert/strict';
import { createAuthClient } from '../../main/resources/migration/auth.mjs';

function fixture(options = {}) {
  let session = 'refresh';
  let time = 0;
  const calls = [];
  const client = createAuthClient({
    storage: { getItem: key => key === 'openhab.ui:refreshToken' ? session : null },
    origin: 'https://openhab.test', cookie: () => options.cookie || '', now: () => time,
    fetch: async (url, init) => { calls.push({ url, init }); return options.fetch ? options.fetch(url, init) : new Response(JSON.stringify({ access_token: 'access', expires_in: 60 }), { status: 200 }); }
  });
  return { client, calls, logout: () => { session = null; }, change: value => { session = value; }, advance: () => { time = 61000; } };
}

test('exchange uses form body and memory-only bearer token with expiry', async () => {
  const f = fixture();
  assert.deepEqual(await f.client.headers(), { Authorization: 'Bearer access' });
  const body = new URLSearchParams(f.calls[0].init.body);
  assert.equal(f.calls[0].url, '/rest/auth/token');
  assert.equal(body.get('client_id'), 'https://openhab.test');
  assert.equal(body.get('redirect_uri'), 'https://openhab.test');
  assert.equal(body.get('refresh_token'), 'refresh');
  assert.equal(body.get('grant_type'), 'refresh_token');
  await f.client.headers(); assert.equal(f.calls.length, 1);
  f.advance(); await f.client.headers(); assert.equal(f.calls.length, 2);
});

test('concurrent exchanges share one request and use custom header', async () => {
  const f = fixture({ cookie: 'X-OPENHAB-AUTH-HEADER=1' });
  const results = await Promise.all([f.client.headers(), f.client.headers()]);
  assert.equal(f.calls.length, 1);
  assert.deepEqual(results[0], { 'X-OPENHAB-TOKEN': 'access' });
});

test('logout during exchange cannot restore access', async () => {
  let release;
  const f = fixture({ fetch: () => new Promise(resolve => { release = resolve; }) });
  const pending = f.client.headers(); f.logout();
  release(new Response(JSON.stringify({ access_token: 'access', expires_in: 60 })));
  await assert.rejects(pending, /Sign in/);
  await assert.rejects(f.client.headers(), /Sign in/);
});

test('exchange failure and malformed response fail without credential diagnostics', async () => {
  for (const fetch of [async () => new Response('secret', {status: 401}),
    async () => { throw new Error('refresh secret'); }, async () => new Response('{}')]) {
    const f = fixture({ fetch });
    await assert.rejects(f.client.headers(), /Session exchange failed/);
  }
});

test('logout invalidates previously cached access', async () => {
  const f = fixture(); await f.client.headers(); f.logout();
  await assert.rejects(f.client.headers(), /Sign in/);
});

test('token replacement exchanges credentials for the current user', async () => {
  const f = fixture({fetch: async (url, init) => new Response(JSON.stringify({
    access_token: new URLSearchParams(init.body).get('refresh_token'), expires_in: 60
  }))});
  await f.client.headers(); f.change('second-user');
  assert.deepEqual(await f.client.headers(), {Authorization: 'Bearer second-user'});
  assert.equal(f.calls.length, 2);
});

test('superseded token exchanges cannot invalidate the current user session', async () => {
  for (const status of [200, 401]) {
    const waiting = [];
    const f = fixture({fetch: () => new Promise(resolve => waiting.push(resolve))});
    const old = f.client.headers();
    f.change('second-user'); f.client.invalidate();
    const current = f.client.headers();
    waiting[1](new Response(JSON.stringify({access_token:'second-access',expires_in:60})));
    assert.deepEqual(await current, {Authorization:'Bearer second-access'});
    const version = f.client.sessionVersion();
    waiting[0](new Response(JSON.stringify({access_token:'old-access',expires_in:60}), {status}));
    await assert.rejects(old);
    assert.equal(f.client.sessionVersion(), version);
    assert.deepEqual(await f.client.headers(), {Authorization:'Bearer second-access'});
    assert.equal(f.calls.length, 2);
  }
});

test('missing Main UI session fails without a request', async () => {
  const f = fixture(); f.logout();
  await assert.rejects(f.client.headers(), /Sign in/);
  assert.equal(f.calls.length, 0);
});
