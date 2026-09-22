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

/** Declared once in app.js; asserted against that list at the bottom of this file. */
const COMMAND_COUNT = 10;
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

  // Evaluated with the source rather than after it. Each window.eval gets its
  // own lexical scope, so a second call cannot see the app's `const` state at
  // all; this closes over it instead. Nothing in app.js knows about it.
  window.eval(`${source}
    ;window.__app = {
      asAction,
      join(channel, nick, members) {
        active = channel;
        myNick = nick;
        memberLists.set(channel, members);
      },
      capture() {
        const sent = [];
        send = (command) => sent.push(command);
        return sent;
      },
      receive(event) { handle(event); },
    };`);

  const input = window.document.getElementById('input');
  const menu = window.document.getElementById('completions');

  return {
    input,
    menu,
    window,
    app: window.__app,
    /** Puts the page in a channel with people in it. */
    inChannel(members) {
      window.__app.join('#test', 'tester', members);
    },
    /** A line from the server, as the socket would deliver it. */
    receive(state, detail) { window.__app.receive({ type: 'server', state, detail }); },
    /** Every line currently in the visible buffer. */
    lines() {
      return [...window.document.querySelectorAll('#log li')]
          .map((li) => li.querySelector('.body').textContent);
    },
    submit() {
      window.document.getElementById('say')
          .dispatchEvent(new window.Event('submit', { cancelable: true }));
    },
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
  assert.equal(ui.offered().length, COMMAND_COUNT);
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

// ------------------------------------------------------------ nick completion

const CHANNEL = [
  { nick: 'alice', prefix: '@', operator: true },
  { nick: 'alan', prefix: '', operator: false },
  { nick: 'bob', prefix: '', operator: false },
  { nick: 'tester', prefix: '', operator: false },
];

test('a word that is not a command completes against who is here', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('al');
  assert.deepEqual(ui.offered(), ['alice', 'alan']);
});

test('your own nick is not offered', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('tes');
  assert.equal(ui.offered(), null, 'completing your own nick helps nobody');
});

test('nobody is offered outside a channel', () => {
  const ui = page();
  ui.type('al');
  assert.equal(ui.offered(), null);
});

test('a nick at the start of a line is addressed with a colon', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('ali');
  ui.key('Tab');
  assert.equal(ui.input.value, 'alice: ', 'the convention other clients highlight on');
});

test('a nick in the middle of a sentence is just the nick', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('ask ali');
  ui.key('Tab');
  assert.equal(ui.input.value, 'ask alice ');
});

test('completing a nick leaves the rest of the line alone', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('ask ali about it', 7);
  ui.key('Tab');

  // One space, not two: the completion brings its own and there was already
  // one there.
  assert.equal(ui.input.value, 'ask alice about it');
});

test('Enter never completes a nick', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('hey al');

  // The whole message would change under someone mid-sentence otherwise:
  // "hey al" and a return would send "hey alice".
  assert.equal(ui.key('Enter'), false, 'Enter has to send');
  assert.equal(ui.input.value, 'hey al');
});

test('Tab still completes commands when a channel is open', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  ui.type('/j');
  ui.key('Tab');
  assert.equal(ui.input.value, '/join ');
});

// -------------------------------------------------------------------- actions

test('an incoming action is recognised and unwrapped', () => {
  const { asAction } = page().app;

  assert.equal(asAction('\u0001ACTION waves\u0001'), 'waves');
  assert.equal(asAction('\u0001ACTION waves'), 'waves',
      'the closing marker is optional, and not every client sends it');
  assert.equal(asAction('an ordinary message'), null);
  assert.equal(asAction(null), null);
});

test('/me renders as an action rather than control characters', () => {
  const ui = page();
  ui.inChannel(CHANNEL);
  const sent = ui.app.capture();

  ui.type('/me waves');
  ui.submit();

  const [command] = sent;
  assert.equal(command.type, 'message');
  assert.equal(command.target, '#test');
  assert.equal(command.text, '\u0001ACTION waves\u0001');

  const line = ui.window.document.querySelector('#log li.action');
  assert.ok(line, 'the action should have been echoed into the log');
  assert.equal(line.querySelector('.body').textContent, 'waves',
      'the markers belong on the wire, not on the screen');
});

// ------------------------------------------------------------- channel lists

/** A LIST reply, deliberately not in size order. */
function listReply(ui, channels) {
  ui.receive('321', 'Channel :Users  Name');
  for (const [channel, users, topic] of channels) {
    ui.receive('322', `${channel} ${users} ${topic}`);
  }
  ui.receive('323', ':End of /LIST');
}

test('a channel list comes back busiest first', () => {
  const ui = page();
  listReply(ui, [['#small', 3, 'a quiet corner'], ['#huge', 900, 'everyone'],
                 ['#middle', 40, 'some people']]);

  const channels = ui.lines().filter((line) => line.startsWith('#'))
      .map((line) => line.split(' ')[0]);
  assert.deepEqual(channels, ['#huge', '#middle', '#small']);
});

test('the list says how many channels there were', () => {
  const ui = page();
  listReply(ui, [['#a', 1, 'x'], ['#b', 2, 'y']]);
  assert.ok(ui.lines().some((line) => line.includes('2 channels')));
});

test('channels of the same size keep a stable order', () => {
  const ui = page();
  listReply(ui, [['#zulu', 5, ''], ['#alpha', 5, '']]);

  const channels = ui.lines().filter((line) => line.startsWith('#'))
      .map((line) => line.split(' ')[0]);
  assert.deepEqual(channels, ['#alpha', '#zulu'],
      'alphabetical among equals, rather than whatever order the server sent');
});

test('a huge list is cut to the busiest rather than filling the buffer', () => {
  const ui = page();
  const many = Array.from({ length: 250 }, (unused, i) => [`#c${i}`, i, '']);
  listReply(ui, many);

  const channels = ui.lines().filter((line) => line.startsWith('#'));
  assert.equal(channels.length, 100);
  assert.ok(channels[0].startsWith('#c249'), 'the busiest should survive the cut');
  assert.ok(ui.lines().some((line) => line.includes('250 channels')),
      'and it should still say what was left out');
});

test('an empty list says so rather than showing nothing', () => {
  const ui = page();
  listReply(ui, []);
  assert.ok(ui.lines().some((line) => line.includes('no channels listed')));
});

test('an ordinary numeric is still shown as it arrives', () => {
  const ui = page();
  ui.receive('331', '#test :No topic is set');
  assert.ok(ui.lines().some((line) => line.includes('No topic is set')));
});
