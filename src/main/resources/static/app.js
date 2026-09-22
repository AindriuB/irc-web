'use strict';

// One socket, one IRC connection. Messages are JSON in both directions and are
// switched on `type`; see OutboundEvent and ClientCommand on the server.

const $ = (id) => document.getElementById(id);

const ui = {
  state: $('state'),
  server: $('server'),
  notes: $('server-notes'),
  nick: $('nick'),
  channels: $('channels'),
  password: $('password'),
  saslUser: $('sasl-username'),
  saslPass: $('sasl-password'),
  go: $('go'),
  stop: $('stop'),
  tabs: $('channel-tabs'),
  log: $('log'),
  topic: $('topic'),
  input: $('input'),
  sayButton: document.querySelector('#say button'),
  members: $('members'),
  memberCount: $('member-count'),
  right: $('right'),
};

let socket = null;
let servers = [];
let active = null;              // the channel currently being viewed
const buffers = new Map();      // target -> [{kind, sender, text, at}]
const memberLists = new Map();  // channel -> [{nick, prefix, operator}]
const topics = new Map();

// Two buffers that are not channels but behave like them, so everything gets the
// full width of the centre pane rather than a column squeezed in beside it.
const STATUS_BUFFER = '*status*';
const WIRE_BUFFER = '*wire*';
const PINNED = [STATUS_BUFFER, WIRE_BUFFER];

const LABELS = { [STATUS_BUFFER]: 'status', [WIRE_BUFFER]: 'wire traffic' };

// ---------------------------------------------------------------- server list

async function loadServers() {
  const response = await fetch('/api/servers');
  servers = await response.json();
  ui.server.innerHTML = '';
  for (const server of servers) {
    const option = document.createElement('option');
    option.value = server.id;
    option.textContent = `${server.name} — ${server.host}:${server.port}` +
      (server.tls ? ' (TLS)' : '');
    ui.server.append(option);
  }
  showNotes();
}

function showNotes() {
  const server = servers.find((s) => s.id === ui.server.value);
  if (!server) { return; }
  const exercises = (server.exercises || []).join(', ');
  ui.notes.textContent = server.notes.trim();
  if (exercises) {
    ui.notes.textContent += ` Exercises: ${exercises}.`;
  }
  // Only offer SASL where it will work, rather than letting someone fill in
  // three fields that the network will ignore.
  $('auth').open = server.sasl || server.registered;
  ui.saslUser.disabled = !server.sasl;
  ui.saslPass.disabled = !server.sasl;
}

// -------------------------------------------------------------------- socket

function connect(event) {
  event.preventDefault();
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/irc`;
  socket = new WebSocket(url);

  socket.addEventListener('open', () => {
    send({
      type: 'connect',
      serverId: ui.server.value,
      nick: ui.nick.value.trim(),
      password: ui.password.value || null,
      saslUsername: ui.saslUser.value || null,
      saslPassword: ui.saslPass.value || null,
      channels: splitChannels(ui.channels.value),
    });
  });

  socket.addEventListener('message', (frame) => handle(JSON.parse(frame.data)));
  socket.addEventListener('close', () => setState('disconnected', 'socket closed'));
  socket.addEventListener('error', () => setState('disconnected', 'socket error'));
}

function send(command) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(command));
  }
}

function disconnect() {
  send({ type: 'disconnect' });
  if (socket) { socket.close(); }
}

// ------------------------------------------------------------------ handling

function handle(event) {
  switch (event.type) {
    case 'status':
      setState(event.state, event.detail);
      if (event.detail) { append(STATUS_BUFFER, 'system', null, event.detail); }
      break;

    case 'message':
      append(event.target, event.self ? 'self' : 'message', event.sender, event.text);
      break;

    case 'notice':
      append(event.target || STATUS_BUFFER, 'notice', event.sender, event.text);
      break;

    case 'presence': {
      const where = event.channel || STATUS_BUFFER;
      const what = event.kind === 'quit'
        ? `${event.nick} quit${event.reason ? ` (${event.reason})` : ''}`
        : `${event.nick} ${event.kind === 'join' ? 'joined' : 'left'} ${event.channel}`;
      append(where, 'system', null, what);
      break;
    }

    case 'channels':
      renderTabs(event.channels || []);
      break;

    case 'names':
      memberLists.set(event.channel, event.members || []);
      if (event.topic) { topics.set(event.channel, event.topic); }
      if (event.channel === active) { renderMembers(); renderTopic(); }
      break;

    case 'raw':
      append(WIRE_BUFFER, event.direction === 'out' ? 'raw-out' : 'raw-in', null, event.line);
      break;

    case 'error':
      append(STATUS_BUFFER, 'error', null, event.detail);
      break;
  }
}

function setState(state, detail) {
  ui.state.textContent = detail ? `${state} — ${detail}` : state;
  ui.state.className = `state ${state}`;
  const live = state === 'ready';
  ui.input.disabled = !live;
  ui.sayButton.disabled = !live;
  ui.stop.disabled = !(live || state === 'connecting');
  ui.go.disabled = live || state === 'connecting';
  if (state === 'disconnected') {
    memberLists.clear();
    renderMembers();
  }
}

// ------------------------------------------------------------------ rendering

function append(target, kind, sender, text) {
  if (!buffers.has(target)) { buffers.set(target, []); }
  const buffer = buffers.get(target);
  buffer.push({ kind, sender, text, at: new Date() });
  // A busy channel will fill memory over a long session otherwise, and this is
  // meant to be left running.
  if (buffer.length > 2000) { buffer.splice(0, buffer.length - 2000); }

  if (!active) { select(target); }
  if (target === active) { renderLog(); }
  if (!ui.tabs.querySelector(`[data-target="${cssEscape(target)}"]`)) { addTab(target); }
}

function renderLog() {
  const buffer = buffers.get(active) || [];
  ui.log.replaceChildren(...buffer.map((entry) => {
    const li = document.createElement('li');
    li.className = entry.kind;
    const time = document.createElement('time');
    time.textContent = entry.at.toTimeString().slice(0, 8);
    li.append(time);
    if (entry.kind === 'raw-in' || entry.kind === 'raw-out') {
      const arrow = document.createElement('span');
      arrow.className = 'arrow';
      arrow.textContent = entry.kind === 'raw-out' ? '>>' : '<<';
      li.append(arrow);
    }
    if (entry.sender) {
      const who = document.createElement('span');
      who.className = 'who';
      who.textContent = entry.sender;
      li.append(who);
    }
    const body = document.createElement('span');
    body.className = 'body';
    body.textContent = entry.text;
    li.append(body);
    return li;
  }));
  ui.log.scrollTop = ui.log.scrollHeight;
}

function renderTabs(channels) {
  const wanted = [...PINNED, ...channels];
  for (const target of wanted) {
    if (!ui.tabs.querySelector(`[data-target="${cssEscape(target)}"]`)) { addTab(target); }
  }
  for (const li of [...ui.tabs.children]) {
    if (!wanted.includes(li.dataset.target)) { li.remove(); }
  }
}

function addTab(target) {
  const li = document.createElement('li');
  li.dataset.target = target;
  li.textContent = LABELS[target] || target;
  if (PINNED.includes(target)) { li.classList.add('pinned'); }
  li.addEventListener('click', () => select(target));
  if (target === active) { li.classList.add('active'); }
  ui.tabs.append(li);
}

function select(target) {
  active = target;
  for (const li of ui.tabs.children) {
    li.classList.toggle('active', li.dataset.target === target);
  }
  const channel = isChannel(target);
  // A member list beside the wire log is empty and just narrows the thing you
  // are trying to read.
  ui.right.hidden = !channel;
  ui.log.classList.toggle('wire', target === WIRE_BUFFER);
  ui.input.placeholder = target === WIRE_BUFFER
    ? 'raw IRC line, sent exactly as typed - e.g. LIST or WHOIS someone'
    : 'message, or /join #chan, /part, /raw LIST';
  renderLog();
  renderMembers();
  renderTopic();
}

function isChannel(target) {
  return target && !PINNED.includes(target);
}

function renderMembers() {
  const members = memberLists.get(active) || [];
  ui.memberCount.textContent = members.length ? `(${members.length})` : '';
  ui.members.replaceChildren(...members.map((member) => {
    const li = document.createElement('li');
    if (member.operator) { li.classList.add('op'); }
    li.textContent = (member.prefix || '') + member.nick;
    return li;
  }));
}

function renderTopic() {
  const topic = topics.get(active);
  ui.topic.textContent = topic || '';
  ui.topic.hidden = !topic;
}

// -------------------------------------------------------------------- input

function say(event) {
  event.preventDefault();
  const text = ui.input.value.trim();
  if (!text) { return; }
  ui.input.value = '';

  if (text.startsWith('/')) {
    const [command, ...rest] = text.slice(1).split(/\s+/);
    const argument = rest.join(' ');
    switch (command.toLowerCase()) {
      case 'join': send({ type: 'join', channel: argument }); return;
      case 'part': send({ type: 'part', channel: argument || active }); return;
      case 'raw': send({ type: 'raw', line: argument }); return;
      case 'msg': {
        const [target, ...words] = rest;
        send({ type: 'message', target, text: words.join(' ') });
        return;
      }
      default:
        append(STATUS_BUFFER, 'error', null, `unknown command /${command}`);
        return;
    }
  }

  if (active === WIRE_BUFFER) {
    send({ type: 'raw', line: text });
    return;
  }
  if (!isChannel(active)) {
    append(STATUS_BUFFER, 'error', null, 'pick a channel first, or use /msg');
    return;
  }
  send({ type: 'message', target: active, text });
}

function splitChannels(value) {
  return value.split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);
}

// Attribute selectors choke on '#', which every channel name starts with.
function cssEscape(value) {
  return window.CSS && CSS.escape ? CSS.escape(value) : value.replace(/#/g, '\\#');
}

// --------------------------------------------------------------------- wiring

$('connect').addEventListener('submit', connect);
ui.stop.addEventListener('click', disconnect);
ui.server.addEventListener('change', showNotes);
$('say').addEventListener('submit', say);
// The pinned buffers exist before anything connects, so there is somewhere for
// early status and wire lines to land.
renderTabs([]);
select(STATUS_BUFFER);

loadServers();
