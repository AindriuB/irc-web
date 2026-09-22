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
  raw: $('raw'),
  rawLog: $('raw-log'),
};

let socket = null;
let servers = [];
let active = null;              // the channel currently being viewed
const buffers = new Map();      // target -> [{kind, sender, text, at}]
const memberLists = new Map();  // channel -> [{nick, prefix, operator}]
const topics = new Map();

const STATUS_BUFFER = '*status*';

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
      appendRaw(event.direction, event.line);
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
  const wanted = [STATUS_BUFFER, ...channels];
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
  li.textContent = target === STATUS_BUFFER ? 'status' : target;
  li.addEventListener('click', () => select(target));
  if (target === active) { li.classList.add('active'); }
  ui.tabs.append(li);
}

function select(target) {
  active = target;
  for (const li of ui.tabs.children) {
    li.classList.toggle('active', li.dataset.target === target);
  }
  renderLog();
  renderMembers();
  renderTopic();
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

function appendRaw(direction, line) {
  const li = document.createElement('li');
  li.className = direction;
  li.textContent = `${direction === 'in' ? '<<' : '>>'} ${line}`;
  ui.rawLog.append(li);
  while (ui.rawLog.children.length > 500) { ui.rawLog.firstChild.remove(); }
  ui.rawLog.scrollTop = ui.rawLog.scrollHeight;
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

  if (!active || active === STATUS_BUFFER) {
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
$('toggle-raw').addEventListener('click', () => { ui.raw.hidden = !ui.raw.hidden; });

loadServers();
