// The `reconnecting` status task 03 sends while irc-client retries in the
// background: the page keeps Stop usable, refuses a second Connect, shows the
// attempt on the state line and in the status buffer, and — unlike a connect
// failure — a gave-up `disconnected` after it never lands next to the
// profile form.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { STATIC, fakeFetch, page as pageWith, settle } from './helpers.mjs';

const CSS = readFileSync(new URL('style.css', STATIC), 'utf8');

function page() {
  return pageWith(fakeFetch());
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

  // Not just the class name: style.css actually has to style it, with the
  // same custom property .connecting uses, and no new hardcoded colour.
  const rule = CSS.split('\n').find((line) => /\.state\.reconnecting\b/.test(line));
  assert.ok(rule, '.state.reconnecting should have a rule in style.css');
  assert.match(rule, /\.state\.reconnecting\b[^{]*\{[^}]*var\(--warn\)/);
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
  const systemLines = [...log.querySelectorAll('li.system')].map((li) => li.textContent);
  assert.ok(systemLines.some((text) => text.includes('lost the connection to Test')));
  assert.ok(systemLines.some((text) => text.includes('reconnecting to Test (attempt 1, in 2s)')));
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
