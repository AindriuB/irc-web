// The command menu in the chat input.
//
// jsdom rather than a real browser: what is being tested is which command is
// offered, what Tab and Enter do to the text, and that nothing is offered that
// the dispatcher cannot run. None of that needs painting, and a headless
// browser would make the suite slow enough that it stopped being run.

import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const STATIC = new URL('../../main/resources/static/', import.meta.url);
const source = readFileSync(new URL('app.js', STATIC), 'utf8');

function page() {
  const dom = new JSDOM(readFileSync(new URL('index.html', STATIC), 'utf8'),
      { runScripts: 'outside-only', url: 'http://localhost/' });
  const { window } = dom;

  // The page calls these as it loads. They only need to get out of the way.
  window.fetch = async (path) => ({
    ok: true, status: 200,
    json: async () => (path === '/api/me' ? { username: 'tester' } : []),
  });
  window.WebSocket = class { addEventListener() {} send() {} close() {} };
  window.eval(source);

  const input = window.document.getElementById('input');
  const menu = window.document.getElementById('completions');

  return {
    input,
    menu,
    type(value, caret = value.length) {
      input.value = value;
      input.setSelectionRange(caret, caret);
      input.dispatchEvent(new window.Event('input'));
    },
    /** The command names on offer, or null when the menu is closed. */
    offered() {
      return menu.hidden
          ? null
          : [...menu.querySelectorAll('.c-name')].map((n) => n.textContent);
    },
    highlighted() {
      return menu.querySelector('.completion.on .c-name').textContent;
    },
    /** Returns whether the key was swallowed, which is half of what matters. */
    key(name) {
      const event = new window.KeyboardEvent('keydown',
          { key: name, cancelable: true, bubbles: true });
      input.dispatchEvent(event);
      return event.defaultPrevented;
    },
  };
}

test('ordinary text is left alone', () => {
  const ui = page();
  ui.type('hello there');
  assert.equal(ui.offered(), null);
});

test('a bare slash offers every command', () => {
  const ui = page();
  ui.type('/');
  assert.equal(ui.offered().length, 6);
});

test('typing narrows the list', () => {
  const ui = page();
  ui.type('/j');
  assert.deepEqual(ui.offered(), ['/join']);
  ui.type('/q');
  assert.deepEqual(ui.offered(), ['/query']);
});

test('something that matches nothing closes the menu', () => {
  const ui = page();
  ui.type('/zz');
  assert.equal(ui.offered(), null);
});

test('a command already typed in full has nothing left to suggest', () => {
  const ui = page();
  ui.type('/join');
  assert.equal(ui.offered(), null);
});

test('the menu is only for the command word, not its arguments', () => {
  const ui = page();
  ui.type('/join #chan');
  assert.equal(ui.offered(), null, 'writing an argument should not offer commands');

  // Moving back into the command word brings it back. A partial command,
  // because a complete one has nothing to suggest by the rule above.
  ui.type('/jo #chan', 3);
  assert.deepEqual(ui.offered(), ['/join']);
});

test('Tab completes, and leaves the caret ready for an argument', () => {
  const ui = page();
  ui.type('/j');

  assert.equal(ui.key('Tab'), true, 'Tab should not move focus out of the input');
  assert.equal(ui.input.value, '/join ');
  assert.equal(ui.input.selectionStart, 6);
  assert.equal(ui.menu.hidden, true);
});

test('completing keeps an argument that was already typed', () => {
  const ui = page();
  ui.type('/j #already-typed', 2);
  ui.key('Tab');
  assert.equal(ui.input.value, '/join #already-typed');
});

test('the arrows move through the list and wrap', () => {
  const ui = page();
  ui.type('/');
  ui.key('ArrowDown');
  assert.equal(ui.highlighted(), '/part');

  ui.key('ArrowUp');
  ui.key('ArrowUp');
  assert.equal(ui.highlighted(), '/raw', 'going up from the first should wrap to the last');
});

test('Enter finishes an unfinished command', () => {
  const ui = page();
  ui.type('/j');

  assert.equal(ui.key('Enter'), true, 'it should complete rather than send');
  assert.equal(ui.input.value, '/join ');
});

test('Enter on a command that is already complete still sends', () => {
  const ui = page();
  ui.type('/part');

  // The important half of the behaviour: typing a whole command and pressing
  // return has to keep working, or the menu has made the input worse.
  assert.equal(ui.key('Enter'), false);
});

test('Escape closes the menu without touching the text', () => {
  const ui = page();
  ui.type('/');

  assert.equal(ui.key('Escape'), true);
  assert.equal(ui.menu.hidden, true);
  assert.equal(ui.input.value, '/');
});

test('nothing is offered that the dispatcher cannot run', () => {
  const ui = page();
  ui.type('/');

  // Both come from the same table, so this asserts that it stayed that way.
  const declared = [...source.matchAll(/^\s*name: '([a-z]+)',$/gm)].map((m) => m[1]);
  assert.deepEqual(ui.offered().map((name) => name.slice(1)), declared);
});
