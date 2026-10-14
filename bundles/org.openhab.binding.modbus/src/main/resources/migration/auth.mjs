// Reuses the explicitly approved HABPanel/Main UI refresh session. Tokens stay in memory.
export function createAuthClient({ storage = localStorage, origin = location.origin,
  cookie = () => document.cookie, now = Date.now, fetch = globalThis.fetch } = {}) {
  const key = 'openhab.ui:refreshToken';
  let token, pending;
  let expiresAt = 0;
  let generation = 0;
  const signIn = () => new Error('Sign in to Main UI as an administrator, then return here.');
  function invalidate() { token = undefined; expiresAt = 0; generation++; pending = undefined; }
  async function headers() {
    const refresh = storage.getItem(key);
    if (!refresh) { invalidate(); throw signIn(); }
    if (!token || now() >= expiresAt) {
      if (!pending) {
        const version = generation;
        pending = (async () => {
          let data;
          try {
            const response = await fetch('/rest/auth/token', { method: 'POST', credentials: 'same-origin',
              cache: 'no-store', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: new URLSearchParams({ grant_type: 'refresh_token', client_id: origin,
                redirect_uri: origin, refresh_token: refresh }).toString() });
            if (!response.ok) throw new Error();
            data = await response.json();
            if (typeof data.access_token !== 'string' || !data.access_token ||
                !Number.isFinite(Number(data.expires_in)) || Number(data.expires_in) <= 0) throw new Error();
          } catch {
            token = undefined;
            throw new Error('Session exchange failed. Sign in again in Main UI.');
          }
          if (version !== generation || storage.getItem(key) !== refresh) { invalidate(); throw signIn(); }
          token = data.access_token;
          expiresAt = now() + Math.max(0, Number(data.expires_in) * 1000 - 5000);
        })();
      }
      const exchange = pending;
      try { await exchange; } finally { if (pending === exchange) pending = undefined; }
    }
    if (storage.getItem(key) !== refresh) { invalidate(); throw signIn(); }
    return cookie().split(';').some(part => part.trim().split('=')[0] === 'X-OPENHAB-AUTH-HEADER')
      ? { 'X-OPENHAB-TOKEN': token } : { Authorization: `Bearer ${token}` };
  }
  return { headers, invalidate };
}
