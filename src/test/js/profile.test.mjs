// A failed profile save or a failed connect attempt has to show its reason
// next to the form, not just in the status line or the log — and never leak
// what was typed into a password field along the way.

import test from 'node:test';
import assert from 'node:assert/strict';
import { fakeFetch, page, settle } from './helpers.mjs';

// Task 01's PASS_LINE_HINT: the message the server sends back for a server
// password that looks like a whole PASS line rather than just the token.
const HINT = 'Enter just the token (oauth:...), not the whole PASS line';
const SECRET = 'PASS oauth:sekrit-token-123';

test('a failed save shows the server error next to the form, styled as an error', async () => {
  const ui = page(fakeFetch({
    putResult: async () => ({ ok: false, status: 400, json: async () => ({ error: HINT }) }),
  }));
  await settle();

  ui.document.getElementById('server').value = 'srv1';
  ui.document.getElementById('save-profile').click();
  await settle();

  const state = ui.document.getElementById('profile-state');
  assert.equal(state.textContent, `could not save: ${HINT}`);
  assert.ok(state.classList.contains('error-text'));
});

test('a later successful save clears the error class', async () => {
  let fail = true;
  const ui = page(fakeFetch({
    putResult: async () => (fail
      ? { ok: false, status: 400, json: async () => ({ error: HINT }) }
      : { ok: true, status: 204, json: async () => null }),
  }));
  await settle();

  ui.document.getElementById('server').value = 'srv1';
  ui.document.getElementById('save-profile').click();
  await settle();

  const state = ui.document.getElementById('profile-state');
  assert.ok(state.classList.contains('error-text'));

  fail = false;
  ui.document.getElementById('save-profile').click();
  await settle();

  assert.ok(!state.classList.contains('error-text'));
});

test('switching servers (loadProfile) also clears a save error', async () => {
  const ui = page(fakeFetch({
    putResult: async () => ({ ok: false, status: 400, json: async () => ({ error: HINT }) }),
  }));
  await settle();

  ui.document.getElementById('server').value = 'srv1';
  ui.document.getElementById('save-profile').click();
  await settle();

  const state = ui.document.getElementById('profile-state');
  assert.ok(state.classList.contains('error-text'));

  ui.document.getElementById('server').dispatchEvent(new ui.window.Event('change'));
  await settle();

  assert.ok(!state.classList.contains('error-text'));
});

test('a failed save keeps what was typed in the password fields, and never shows it', async () => {
  const ui = page(fakeFetch({
    putResult: async () => ({ ok: false, status: 400, json: async () => ({ error: HINT }) }),
  }));
  await settle();

  ui.document.getElementById('server').value = 'srv1';
  const password = ui.document.getElementById('password');
  password.value = SECRET;
  ui.document.getElementById('save-profile').click();
  await settle();

  assert.equal(password.value, SECRET, 'the typed password should survive a failed save');

  const state = ui.document.getElementById('profile-state');
  const statusLine = ui.document.getElementById('state');
  const log = ui.document.getElementById('log');
  assert.ok(!state.textContent.includes(SECRET));
  assert.ok(!statusLine.textContent.includes(SECRET));
  assert.ok(!log.textContent.includes(SECRET));
});

test('a connect failure while connecting shows the reason next to the form and re-enables Connect', async (t) => {
  // The socket going `open` starts a real 5-minute ping interval; mocked
  // timers keep it from holding the test process open after the run ends.
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page(fakeFetch());
  await settle();
  const socket = ui.sockets[0];
  socket.emit('open');
  await settle();

  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'connecting' }) });
  await settle();

  socket.emit('message', {
    data: JSON.stringify({ type: 'status', state: 'disconnected', detail: HINT }),
  });
  await settle();

  const state = ui.document.getElementById('profile-state');
  assert.equal(state.textContent, HINT);
  assert.ok(state.classList.contains('error-text'));
  assert.equal(ui.document.getElementById('go').disabled, false);
});

test('a reconnect that reaches ready clears a previously shown connect failure', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page(fakeFetch());
  await settle();
  const socket = ui.sockets[0];
  socket.emit('open');
  await settle();

  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'connecting' }) });
  await settle();
  socket.emit('message', {
    data: JSON.stringify({ type: 'status', state: 'disconnected', detail: HINT }),
  });
  await settle();

  const state = ui.document.getElementById('profile-state');
  assert.ok(state.classList.contains('error-text'), 'the failed attempt should have shown red');

  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'connecting' }) });
  await settle();
  assert.ok(!state.classList.contains('error-text'), 'a fresh attempt clears the old failure');

  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'ready' }) });
  await settle();
  assert.ok(!state.classList.contains('error-text'));
});

test('a disconnect with no detail leaves the profile state alone', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const ui = page(fakeFetch());
  await settle();
  const socket = ui.sockets[0];
  socket.emit('open');
  await settle();

  const state = ui.document.getElementById('profile-state');
  const before = state.textContent;

  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'connecting' }) });
  await settle();
  socket.emit('message', { data: JSON.stringify({ type: 'status', state: 'disconnected' }) });
  await settle();

  assert.equal(state.textContent, before);
  assert.ok(!state.classList.contains('error-text'));
});
