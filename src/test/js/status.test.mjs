// The `reconnecting` status task 03 sends while irc-client retries in the
// background: the page keeps Stop usable, refuses a second Connect, shows the
// attempt on the state line and in the status buffer, and — unlike a connect
// failure — a gave-up `disconnected` after it never lands next to the
// profile form.

import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const STATIC = new URL('../../main/resources/static/', import.meta.url);
const source = readFileSync(new URL('app.js', STATIC), 'utf8');

const SERVER = {
  id: 'srv1', name: 'Test', host: 'irc.example', port: 6697,
  tls: true, sasl: false, registered: false, features: [], notes: '',
};

/** A fake WebSocket that records every instance created and lets the test drive it. */
function fakeSocketClass(sockets) {
  return class {
    constructor(url) {
      this.url = url;
      this.listeners = {};
      sockets.push(this);
    }

    addEventListener(type, handler) {
      (this.listeners[type] ||= []).push(handler);
    }

    send() {}

    close() {}

    emit(type, event = {}) {
      for (const handler of this.listeners[type] || []) { handler(event); }
    }
  };
}

/** A GET/PUT-aware fetch stub: servers, and an (empty) stored profile. */
function fakeFetch() {
  return async (path, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    if (path === '/api/me') {
      return { ok: true, status: 200, json: async () => ({ username: 'tester' }) };
    }
    if (path === '/api/servers') {
      return { ok: true, status: 200, json: async () => [SERVER] };
    }
    if (path.endsWith('/profile') && method === 'GET') {
      return { ok: true, status: 200, json: async () => ({}) };
    }
    return { ok: true, status: 200, json: async () => ({}) };
  };
}

function page() {
  const dom = new JSDOM(readFileSync(new URL('index.html', STATIC), 'utf8'),
      { runScripts: 'outside-only', url: 'http://localhost/' });
  const { window } = dom;

  const sockets = [];
  window.fetch = fakeFetch();
  window.WebSocket = fakeSocketClass(sockets);
  window.eval(source);

  return { window, document: window.document, sockets };
}

function flush() {
  return new Promise((resolve) => { setImmediate(resolve); });
}

/** Several microtask hops: server load, then profile load, both chained without awaits. */
async function settle() {
  await flush(); await flush(); await flush(); await flush();
}

function status(socket, state, detail) {
  socket.emit('message', {
    data: JSON.stringify({ type: 'status', state, ...(detail ? { detail } : {}) }),
  });
}

async function openReady(ui) {
  await settle();
  const socket = ui.sockets[0];
  socket.emit('open');
  await settle();
  status(socket, 'connecting');
  await settle();
  status(socket, 'ready');
  await settle();
  return socket;
}

test('reconnecting disables input and Say, enables Stop, disables Connect, and shows the detail on the state line', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  const socket = await openReady(ui);

  const detail = 'lost the connection to Test';
  status(socket, 'reconnecting', detail);
  await settle();

  assert.equal(ui.document.getElementById('input').disabled, true);
  assert.equal(ui.document.getElementById('say').querySelector('button').disabled, true);
  assert.equal(ui.document.getElementById('stop').disabled, false);
  assert.equal(ui.document.getElementById('go').disabled, true);

  const state = ui.document.getElementById('state');
  assert.equal(state.textContent, `reconnecting — ${detail}`);
});

test('.state.reconnecting picks up the warn colour, like .state.connecting', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  const socket = await openReady(ui);

  status(socket, 'reconnecting', 'lost the connection to Test');
  await settle();

  const state = ui.document.getElementById('state');
  assert.ok(state.classList.contains('reconnecting'));
  assert.ok(!state.classList.contains('connecting'));
});

test('each reconnecting detail is appended to the status buffer as a system line', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  const socket = await openReady(ui);

  status(socket, 'reconnecting', 'lost the connection to Test');
  await settle();
  status(socket, 'reconnecting', 'reconnecting to Test (attempt 1, in 2s)');
  await settle();

  const log = ui.document.getElementById('log');
  assert.ok(log.textContent.includes('lost the connection to Test'));
  assert.ok(log.textContent.includes('reconnecting to Test (attempt 1, in 2s)'));
});

test('ready after reconnecting re-enables input and Say and clears the reconnecting look', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  const socket = await openReady(ui);

  status(socket, 'reconnecting', 'lost the connection to Test');
  await settle();
  status(socket, 'ready');
  await settle();

  assert.equal(ui.document.getElementById('input').disabled, false);
  assert.equal(ui.document.getElementById('say').querySelector('button').disabled, false);
  const state = ui.document.getElementById('state');
  assert.ok(!state.classList.contains('reconnecting'));
  assert.ok(state.classList.contains('ready'));
});

test('a gave-up disconnect after reconnecting re-enables Connect and shows the detail, but not next to the form', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  const socket = await openReady(ui);
  const profileState = ui.document.getElementById('profile-state');
  const before = profileState.textContent;

  status(socket, 'reconnecting', 'lost the connection to Test');
  await settle();
  status(socket, 'reconnecting', 'reconnecting to Test (attempt 1, in 2s)');
  await settle();

  const detail = 'gave up reconnecting to Test after 5 attempts';
  status(socket, 'disconnected', detail);
  await settle();

  assert.equal(ui.document.getElementById('go').disabled, false);

  const state = ui.document.getElementById('state');
  assert.equal(state.textContent, `disconnected — ${detail}`);

  const log = ui.document.getElementById('log');
  assert.ok(log.textContent.includes(detail));

  assert.equal(profileState.textContent, before,
    'a gave-up reconnect is not a connect failure, so the form slot is untouched');
  assert.ok(!profileState.classList.contains('error-text'));
});

test('a connect failure (connecting then disconnected) still shows the detail next to the form', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page();
  await settle();
  const socket = ui.sockets[0];
  socket.emit('open');
  await settle();

  const detail = 'could not connect';
  status(socket, 'connecting');
  await settle();
  status(socket, 'disconnected', detail);
  await settle();

  const profileState = ui.document.getElementById('profile-state');
  assert.equal(profileState.textContent, detail);
  assert.ok(profileState.classList.contains('error-text'));
  assert.equal(ui.document.getElementById('go').disabled, false);
});
