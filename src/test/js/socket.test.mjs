// The socket lifecycle: keeping the HTTP session alive while it is open, and
// telling a dead session from a network blip before reopening it.
//
// jsdom does not navigate, so a 401 is observed through `redirectToLogin`
// rather than `location.href` — the test replaces it the same way
// completions.test.mjs replaces `send`.

import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { STATIC, source, fakeSocketClass, flush } from './helpers.mjs';

function page() {
  const dom = new JSDOM(readFileSync(new URL('index.html', STATIC), 'utf8'),
      { runScripts: 'outside-only', url: 'http://localhost/' });
  const { window } = dom;

  const sockets = [];
  const calls = [];
  const defaultFetch = async (path) => ({
    ok: true, status: 200,
    json: async () => (path === '/api/me' ? { username: 'tester' } : []),
  });

  // Reassignable so a test can change how `/api/me` answers after the page has
  // already loaded (and made its own, unrelated call to it), without touching
  // the calls the page made on the way up.
  let impl = defaultFetch;

  window.fetch = async (path, options) => {
    calls.push(path);
    return impl(path, options);
  };
  window.WebSocket = fakeSocketClass(sockets);

  window.eval(`${source}
    ;window.__app = {
      redirects: [],
      overrideRedirect() { redirectToLogin = () => { window.__app.redirects.push(1); }; },
    };`);
  window.__app.overrideRedirect();

  return {
    window,
    sockets,
    calls,
    redirectCount: () => window.__app.redirects.length,
    setFetch(newImpl) { impl = newImpl; },
  };
}

test('while the socket is open, a 5-minute interval pings /api/me', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  ui.sockets[0].emit('open');

  ui.calls.length = 0;
  t.mock.timers.tick(300000);
  await flush();
  assert.deepEqual(ui.calls, ['/api/me']);

  ui.calls.length = 0;
  t.mock.timers.tick(300000);
  await flush();
  assert.deepEqual(ui.calls, ['/api/me']);
});

test('the ping interval stops once the socket closes', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  ui.sockets[0].emit('open');
  ui.sockets[0].emit('close');

  // Closing also schedules a reopen probe of its own; let that settle (it
  // succeeds and opens a fresh, still unopened, socket) before asking whether
  // the old ping interval is still ticking.
  t.mock.timers.tick(1000);
  await flush();

  ui.calls.length = 0;
  t.mock.timers.tick(300000);
  await flush();
  assert.deepEqual(ui.calls, [], 'no ping should fire once the socket has closed');
});

test('reopening never leaves more than one ping interval running', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  ui.sockets[0].emit('open');
  ui.sockets[0].emit('close');

  // Probe succeeds, a second socket is opened and also goes open.
  t.mock.timers.tick(1000);
  await flush();
  assert.equal(ui.sockets.length, 2);
  ui.sockets[1].emit('open');

  ui.calls.length = 0;
  t.mock.timers.tick(300000);
  await flush();
  assert.deepEqual(ui.calls, ['/api/me'], 'exactly one interval firing, not two stacked');
});

test('a probe that comes back 401 redirects to login and opens nothing new', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  ui.setFetch(async () => ({ ok: false, status: 401, json: async () => ({}) }));
  ui.sockets[0].emit('close');

  t.mock.timers.tick(1000);
  await flush();

  assert.equal(ui.redirectCount(), 1);
  assert.equal(ui.sockets.length, 1, 'no new socket should be constructed after a 401 probe');

  // And nothing further is scheduled: waiting out the backoff window opens nothing.
  t.mock.timers.tick(60000);
  await flush();
  assert.equal(ui.sockets.length, 1);
});

test('a probe that comes back 200 reopens exactly one socket', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  ui.sockets[0].emit('close');

  t.mock.timers.tick(1000);
  await flush();

  assert.equal(ui.sockets.length, 2);
  assert.equal(ui.redirectCount(), 0);
});

test('a probe that fails on the network backs off and probes again', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  let fail = true;
  const ui = page();
  ui.setFetch(async () => {
    if (fail) { throw new Error('network down'); }
    return { ok: true, status: 200, json: async () => ({ username: 'tester' }) };
  });
  ui.sockets[0].emit('close');

  // First probe at 1s fails, and reopening does not happen.
  t.mock.timers.tick(1000);
  await flush();
  assert.equal(ui.sockets.length, 1);
  assert.equal(ui.redirectCount(), 0);

  // Backoff doubles to 2s; recovery lets the retried probe succeed.
  fail = false;
  t.mock.timers.tick(2000);
  await flush();
  assert.equal(ui.sockets.length, 2, 'the retried probe should have reopened the socket');
});
