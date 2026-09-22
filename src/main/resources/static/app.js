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
  completions: $('completions'),
  sayButton: document.querySelector('#say button'),
  members: $('members'),
  memberCount: $('member-count'),
  right: $('right'),
  menu: $('user-menu'),
  who: $('who'),
  saveProfile: $('save-profile'),
  profileState: $('profile-state'),
  serverDialog: $('server-dialog'),
  passwordDialog: $('password-dialog'),
};

let editingServerId = null;   // null while adding, an id while editing

// ------------------------------------------------------------------- fetch

/**
 * Every call goes through here for two reasons: the CSRF token has to ride
 * along on anything that changes state, and a 401 means the session went away,
 * which is a reload rather than an error message nobody can act on.
 */
async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { ...(options.headers || {}) };
  if (options.body !== undefined) { headers['Content-Type'] = 'application/json'; }
  if (method !== 'GET') {
    const token = readCookie('XSRF-TOKEN');
    if (token) { headers['X-XSRF-TOKEN'] = token; }
  }

  const response = await fetch(path, {
    ...options,
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  if (response.status === 401) {
    location.href = '/login.html';
    throw new Error('signed out');
  }
  if (!response.ok) {
    let detail = `${response.status}`;
    try {
      const body = await response.json();
      if (body && body.error) { detail = body.error; }
    } catch (ignored) { /* a non-JSON error body is still an error */ }
    throw new Error(detail);
  }
  return response.status === 204 ? null : response.json();
}

function readCookie(name) {
  return document.cookie.split('; ')
    .map((pair) => pair.split('='))
    .filter(([key]) => key === name)
    .map(([, value]) => decodeURIComponent(value))[0];
}

let socket = null;
let reopenDelay = 0;            // grows while the socket cannot be reopened
let pendingConnect = null;      // a connect pressed before the socket was ready
let servers = [];
let active = null;              // the channel currently being viewed
let myNick = null;              // as the server gave it, not as it was asked for
const buffers = new Map();      // target -> [{kind, sender, text, at}]

/** CTCP wraps its payload in this. An ACTION is the only one handled here. */
const CTCP = '\u0001';
const unread = new Map();       // target -> {count, mention}
const queries = new Set();      // one-to-one conversations, which the server never lists
const memberLists = new Map();  // channel -> [{nick, prefix, operator}]
const topics = new Map();

// Two buffers that are not channels but behave like them, so everything gets the
// full width of the centre pane rather than a column squeezed in beside it.
const STATUS_BUFFER = '*status*';
const WIRE_BUFFER = '*wire*';
const PINNED = [STATUS_BUFFER, WIRE_BUFFER];

const LABELS = { [STATUS_BUFFER]: 'status', [WIRE_BUFFER]: 'wire traffic' };

// ---------------------------------------------------------------- server list

async function loadMe() {
  const me = await api('/api/me');
  ui.who.textContent = me.username;
}

async function loadServers(selectId) {
  const wanted = selectId || ui.server.value;
  servers = await api('/api/servers');
  ui.server.innerHTML = '';
  for (const server of servers) {
    const option = document.createElement('option');
    option.value = server.id;
    option.textContent = `${server.name} — ${server.host}:${server.port}` +
      (server.tls ? ' (TLS)' : '');
    ui.server.append(option);
  }
  if (wanted && servers.some((s) => s.id === wanted)) { ui.server.value = wanted; }
  showNotes();
  loadProfile();
}

// ------------------------------------------------------------------ profile

async function loadProfile() {
  const id = ui.server.value;
  if (!id) { return; }
  let profile;
  try {
    profile = await api(`/api/servers/${encodeURIComponent(id)}/profile`);
  } catch (e) {
    ui.profileState.textContent = `could not load saved details: ${e.message}`;
    return;
  }

  ui.nick.value = profile.nick || '';
  ui.channels.value = (profile.channels || []).join(', ');
  ui.saslUser.value = profile.saslUsername || '';

  // Stored passwords are never sent here, so the field shows whether one exists
  // rather than what it is. Leaving it blank keeps it; the placeholder says so.
  ui.password.value = '';
  ui.saslPass.value = '';
  ui.password.placeholder = profile.hasPassword
    ? 'stored - leave blank to keep it' : 'or a Twitch oauth: token';
  ui.saslPass.placeholder = profile.hasSaslPassword
    ? 'stored - leave blank to keep it' : '';

  ui.profileState.textContent = profile.updatedAt
    ? `saved ${new Date(profile.updatedAt).toLocaleString()}`
    : 'nothing saved for this server yet';
}

async function saveProfile() {
  const id = ui.server.value;
  const body = {
    nick: ui.nick.value.trim() || null,
    saslUsername: ui.saslUser.value.trim() || null,
    channels: splitChannels(ui.channels.value),
    // null means leave the stored secret alone, which is what an untouched
    // field should mean. Clearing it needs an explicit empty string.
    password: ui.password.value === '' ? null : ui.password.value,
    saslPassword: ui.saslPass.value === '' ? null : ui.saslPass.value,
  };
  try {
    await api(`/api/servers/${encodeURIComponent(id)}/profile`, { method: 'PUT', body });
    await loadProfile();
  } catch (e) {
    ui.profileState.textContent = `could not save: ${e.message}`;
  }
}

function showNotes() {
  const server = servers.find((s) => s.id === ui.server.value);
  if (!server) { return; }
  const features = (server.features || []).join(', ');
  ui.notes.textContent = server.notes.trim();
  if (features) {
    ui.notes.textContent += ` Features: ${features}.`;
  }
  // Only offer SASL where it will work, rather than letting someone fill in
  // three fields that the network will ignore.
  $('auth').open = server.sasl || server.registered;
  ui.saslUser.disabled = !server.sasl;
  ui.saslPass.disabled = !server.sasl;
}

// ------------------------------------------------------- server management

function openServerDialog(server) {
  editingServerId = server ? server.id : null;
  $('server-dialog-title').textContent = server ? `Edit ${server.name}` : 'Add a server';
  $('f-name').value = server ? server.name : '';
  $('f-id').value = server ? server.id : '';
  // The id is the key the profile hangs off, so changing it would orphan the
  // credentials stored against it.
  $('f-id').disabled = Boolean(server);
  $('f-host').value = server ? server.host : '';
  $('f-port').value = server ? server.port : 6697;
  $('f-tls').checked = server ? server.tls : true;
  $('f-sasl').checked = server ? server.sasl : false;
  $('f-registered').checked = server ? server.registered : false;
  $('f-features').value = server ? (server.features || []).join(', ') : '';
  $('f-notes').value = server ? (server.notes || '') : '';
  $('server-error').textContent = '';
  ui.serverDialog.showModal();
}

async function saveServer() {
  const body = {
    id: $('f-id').value.trim() || null,
    name: $('f-name').value.trim(),
    host: $('f-host').value.trim(),
    port: Number($('f-port').value),
    tls: $('f-tls').checked,
    sasl: $('f-sasl').checked,
    registered: $('f-registered').checked,
    features: $('f-features').value.split(/[,\s]+/).map((s) => s.trim()).filter(Boolean),
    notes: $('f-notes').value.trim() || null,
  };
  try {
    const saved = editingServerId
      ? await api(`/api/servers/${encodeURIComponent(editingServerId)}`, { method: 'PUT', body })
      : await api('/api/servers', { method: 'POST', body });
    ui.serverDialog.close();
    await loadServers(saved.id);
  } catch (e) {
    $('server-error').textContent = e.message;
  }
}

async function deleteServer() {
  const server = servers.find((s) => s.id === ui.server.value);
  if (!server) { return; }
  // Deleting takes the stored credentials with it, which is not obvious from
  // the button, so say it before doing it.
  if (!window.confirm(
      `Delete ${server.name}? Any saved nick, channels and passwords for it go too.`)) {
    return;
  }
  try {
    await api(`/api/servers/${encodeURIComponent(server.id)}`, { method: 'DELETE' });
    await loadMe();
loadServers();
  } catch (e) {
    append(STATUS_BUFFER, 'error', null, `could not delete: ${e.message}`);
  }
}

async function changePassword() {
  try {
    await api('/api/me/password', {
      method: 'POST',
      body: { currentPassword: $('p-current').value, newPassword: $('p-new').value },
    });
    ui.passwordDialog.close();
    $('p-current').value = '';
    $('p-new').value = '';
    await loadMe();
    append(STATUS_BUFFER, 'system', null, 'password changed');
  } catch (e) {
    $('password-error').textContent = e.message;
  }
}

// -------------------------------------------------------------------- socket

/**
 * The socket is opened on load, not on Connect.
 *
 * <p>The IRC connection belongs to the account and outlives this page, so the
 * first thing to find out is whether one is already running. Opening the socket
 * is how you ask.
 */
function openSocket() {
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/irc`;
  socket = new WebSocket(url);

  socket.addEventListener('open', () => {
    reopenDelay = 0;
    if (pendingConnect) {
      send(pendingConnect);
      pendingConnect = null;
    }
  });

  socket.addEventListener('message', (frame) => handle(JSON.parse(frame.data)));
  socket.addEventListener('close', () => {
    // Losing the socket says nothing about the IRC connection, which is still
    // sitting in its channels. Get the socket back and it replays what was
    // said in the meantime.
    setState('disconnected', 'lost the page connection, retrying');
    scheduleReopen();
  });
  socket.addEventListener('error', () => { /* close follows, and handles it */ });
}

function scheduleReopen() {
  reopenDelay = reopenDelay ? Math.min(reopenDelay * 2, 15000) : 1000;
  setTimeout(openSocket, reopenDelay);
}

function connect(event) {
  event.preventDefault();
  const command = {
    type: 'connect',
    serverId: ui.server.value,
    nick: ui.nick.value.trim(),
    password: ui.password.value || null,
    saslUsername: ui.saslUser.value || null,
    saslPassword: ui.saslPass.value || null,
    channels: splitChannels(ui.channels.value),
  };

  if (socket && socket.readyState === WebSocket.OPEN) {
    send(command);
    return;
  }
  // Sent as soon as the socket is there, so pressing Connect during a blip
  // does not quietly do nothing.
  pendingConnect = command;
  openSocket();
}

function send(command) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(command));
  }
}

function disconnect() {
  // Only this leaves the network now. The socket stays, so the next connection
  // does not have to wait for one to be opened.
  send({ type: 'disconnect' });
  resetBuffers();
}

// ------------------------------------------------------------------ handling

function handle(event) {
  switch (event.type) {
    case 'status':
      if (event.nick) { myNick = event.nick; }
      setState(event.state, event.detail);
      if (event.detail) { append(STATUS_BUFFER, 'system', null, event.detail); }
      break;

    case 'message': {
      // An action arrives as an ordinary message wrapped in CTCP markers. Left
      // alone it renders as literal control characters around the word ACTION.
      const action = asAction(event.text);
      append(bufferFor(event),
        action !== null ? 'action' : (event.self ? 'self' : 'message'),
        event.sender,
        action !== null ? action : event.text);
      break;
    }

    case 'notice':
      append(isChannelName(event.target) ? event.target : STATUS_BUFFER,
        'notice', event.sender, event.text);
      break;

    case 'server':
      // A LIST arrives as one line per channel in whatever order the server
      // keeps them, which on a big network is thousands of lines nobody can
      // read. Collected between 321 and 323, then shown busiest first.
      if (event.state === '321') { listing = []; break; }
      if (event.state === '322') {
        if (!listing) { listing = []; }
        listing.push(parseListing(event.detail));
        break;
      }
      if (event.state === '323') { showListing(); break; }

      append(STATUS_BUFFER, 'server', event.state, event.detail);
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

    case 'replay':
      // Bracketing a replay lets the browser start from nothing. Without it a
      // socket that dropped and came back would show every message twice.
      if (event.state === 'start') { resetBuffers(); }
      break;

    case 'attached':
      // A session was already running. Show which network it is on, and under
      // what nick, so the form matches what is actually connected.
      if (event.nick) { myNick = event.nick; ui.nick.value = event.nick; }
      if (event.state) { loadServers(event.state); }
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

/**
 * Which buffer a message belongs in. A channel message goes to the channel; a
 * private one goes to a buffer named after the other person, because the target of
 * an incoming private message is our own nick and a buffer named after ourselves is
 * no use to anybody.
 */
function bufferFor(event) {
  if (isChannelName(event.target)) { return event.target; }
  const other = event.self ? event.target : event.sender;
  if (other) { openQuery(other); return other; }
  return STATUS_BUFFER;
}

function isChannelName(target) {
  return Boolean(target) && '#&!+'.includes(target[0]);
}

function openQuery(nick) {
  if (!queries.has(nick)) {
    queries.add(nick);
    if (!ui.tabs.querySelector(`[data-target="${cssEscape(nick)}"]`)) { addTab(nick); }
  }
}

/** Back to an empty window, ready to be filled by a replay. */
function resetBuffers() {
  buffers.clear();
  unread.clear();
  memberLists.clear();
  topics.clear();
  queries.clear();
  listing = null;
  active = null;
  renderTabs([]);
  select(STATUS_BUFFER);
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

// Wire traffic would light up permanently and so would mean nothing, and joins
// and parts are noise rather than something waiting to be read.
const BADGED = new Set(['message', 'notice', 'action']);

/**
 * How many channels a listing shows. A network with forty thousand channels
 * would otherwise push everything else out of the buffer, and the tail of that
 * list is empty channels nobody wants.
 */
const LISTING_SHOWN = 100;

let listing = null;   // rows arriving between 321 and 323, or null

/** One RPL_LIST reply: "#channel 42 some topic". */
function parseListing(detail) {
  const match = /^(\S+) (\d+) ?([\s\S]*)$/.exec(detail || '');
  return match
    ? { channel: match[1], users: Number(match[2]), topic: match[3] }
    : null;
}

function showListing() {
  const rows = (listing || []).filter(Boolean);
  listing = null;

  if (!rows.length) {
    append(STATUS_BUFFER, 'system', null, 'no channels listed');
    return;
  }

  // Busiest first, and alphabetical among equals so the order is stable rather
  // than whatever the server happened to send.
  rows.sort((a, b) => b.users - a.users || a.channel.localeCompare(b.channel));
  const shown = rows.slice(0, LISTING_SHOWN);
  const width = Math.max(...shown.map((row) => row.channel.length));

  push(STATUS_BUFFER, 'system', null, shown.length < rows.length
    ? `${rows.length} channels; the ${shown.length} busiest:`
    : `${rows.length} channels, busiest first:`);
  for (const row of shown) {
    push(STATUS_BUFFER, 'listing', null,
      `${row.channel.padEnd(width)}  ${String(row.users).padStart(5)}  ${row.topic}`);
  }
  settle(STATUS_BUFFER);
  select(STATUS_BUFFER);
}

/** The text of a CTCP ACTION, or null for anything else. */
function asAction(text) {
  // The closing marker is optional because not every client sends it.
  const match = new RegExp(`^${CTCP}ACTION ?([\\s\\S]*?)${CTCP}?$`).exec(text || '');
  return match ? match[1] : null;
}

function append(target, kind, sender, text) {
  push(target, kind, sender, text);
  settle(target);
}

/**
 * Adds a line without touching the DOM. A channel listing arrives as hundreds
 * of lines at once, and rendering the whole buffer after each one is the
 * difference between instant and visibly slow.
 */
function push(target, kind, sender, text) {
  if (!buffers.has(target)) { buffers.set(target, []); }
  const buffer = buffers.get(target);
  buffer.push({ kind, sender, text, at: new Date() });

  if (target !== active && BADGED.has(kind)) {
    const state = unread.get(target) || { count: 0, mention: false };
    state.count += 1;
    state.mention = state.mention || mentionsMe(text);
    unread.set(target, state);
  }
  // A busy channel will fill memory over a long session otherwise, and this is
  // meant to be left running.
  if (buffer.length > 2000) { buffer.splice(0, buffer.length - 2000); }
}

function settle(target) {
  if (!active) { select(target); }
  if (target === active) { renderLog(); }
  if (!ui.tabs.querySelector(`[data-target="${cssEscape(target)}"]`)) { addTab(target); }
  paintTab(target);
}

/**
 * A mention is the nick as a whole word. Substring matching turns every message
 * containing "bot" into a highlight for a nick like "bot", which trains people to
 * ignore the thing entirely.
 */
function mentionsMe(text) {
  if (!myNick || !text) { return false; }
  const escaped = myNick.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^\\w[\\]\\\\\`^{|}-])${escaped}([^\\w[\\]\\\\\`^{|}-]|$)`, 'i')
    .test(text);
}

function renderLog() {
  const buffer = buffers.get(active) || [];
  ui.log.replaceChildren(...buffer.map((entry) => {
    const li = document.createElement('li');
    li.className = entry.kind;
    const time = document.createElement('time');
    time.textContent = entry.at.toTimeString().slice(0, 8);
    li.append(time);
    if (entry.kind === 'action') {
      const star = document.createElement('span');
      star.className = 'arrow';
      star.textContent = '*';
      li.append(star);
    }
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
  const wanted = [...PINNED, ...channels, ...queries];
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
  if (PINNED.includes(target)) { li.classList.add('pinned'); }

  const label = document.createElement('span');
  label.className = 'label';
  label.textContent = LABELS[target] || target;
  li.append(label);

  const badge = document.createElement('span');
  badge.className = 'badge';
  li.append(badge);

  li.addEventListener('click', () => select(target));
  if (target === active) { li.classList.add('active'); }
  ui.tabs.append(li);
  paintTab(target);
}

function paintTab(target) {
  const li = ui.tabs.querySelector(`[data-target="${cssEscape(target)}"]`);
  if (!li) { return; }
  const state = unread.get(target);
  const badge = li.querySelector('.badge');
  li.classList.toggle('unread', Boolean(state && state.count));
  li.classList.toggle('mention', Boolean(state && state.mention));
  badge.textContent = state && state.count ? (state.count > 99 ? '99+' : state.count) : '';
}

function select(target) {
  active = target;
  // Reading it is what clears it.
  unread.delete(target);
  paintTab(target);
  for (const li of ui.tabs.children) {
    li.classList.toggle('active', li.dataset.target === target);
  }
  // A member list beside the wire log or a one-to-one conversation is empty and
  // just narrows the thing you are trying to read.
  ui.right.hidden = !isChannelName(target);
  ui.log.classList.toggle('wire', target === WIRE_BUFFER);
  ui.input.placeholder = target === WIRE_BUFFER
    ? 'raw IRC line, sent exactly as typed - e.g. LIST or WHOIS someone'
    : 'message, or / for commands';
  renderLog();
  renderMembers();
  renderTopic();
  paintTab(target);
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
    if (member.nick === myNick) { li.classList.add('self'); }
    li.textContent = (member.prefix || '') + member.nick;
    li.addEventListener('click', (event) => openUserMenu(event, member));
    return li;
  }));
}

// ---------------------------------------------------------------- user menu

/**
 * What can be done to someone depends on what we are: the moderation items only
 * appear when we hold op in this channel, because offering an action the server
 * will refuse teaches people to ignore the menu.
 */
function userMenuItems(member) {
  const nick = member.nick;
  const channel = active;
  const items = [
    { label: `Message ${nick}`, run: () => { openQuery(nick); select(nick); } },
    { label: 'Whois', run: () => { send({ type: 'raw', line: `WHOIS ${nick}` }); select(STATUS_BUFFER); } },
  ];

  if (nick === myNick) { return items; }

  if (iAmOperatorIn(channel)) {
    items.push({ separator: true });
    items.push(member.operator
      ? { label: 'Take op', run: () => mode(channel, '-o', nick) }
      : { label: 'Give op', run: () => mode(channel, '+o', nick) });
    items.push(member.prefix === '+'
      ? { label: 'Take voice', run: () => mode(channel, '-v', nick) }
      : { label: 'Give voice', run: () => mode(channel, '+v', nick) });
    items.push({
      label: 'Kick…',
      run: () => {
        const reason = window.prompt(`Kick ${nick} from ${channel}?`, '');
        if (reason === null) { return; }
        send({ type: 'raw', line: reason.trim()
          ? `KICK ${channel} ${nick} :${reason.trim()}`
          : `KICK ${channel} ${nick}` });
      },
    });
  }
  return items;
}

function iAmOperatorIn(channel) {
  if (!isChannelName(channel) || !myNick) { return false; }
  const me = (memberLists.get(channel) || []).find((m) => m.nick === myNick);
  return Boolean(me && me.operator);
}

function mode(channel, flags, nick) {
  send({ type: 'raw', line: `MODE ${channel} ${flags} ${nick}` });
}

function openUserMenu(event, member) {
  event.stopPropagation();
  const items = userMenuItems(member);

  const heading = document.createElement('div');
  heading.className = 'menu-heading';
  heading.textContent = member.nick;

  ui.menu.replaceChildren(heading, ...items.map((item) => {
    if (item.separator) {
      const hr = document.createElement('hr');
      return hr;
    }
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = item.label;
    button.addEventListener('click', () => { closeUserMenu(); item.run(); });
    return button;
  }));

  ui.menu.hidden = false;
  // Measured after it is visible, or the height is zero and a menu near the
  // bottom of the window runs off the end of it.
  const { innerHeight, innerWidth } = window;
  const rect = ui.menu.getBoundingClientRect();
  const top = Math.min(event.clientY, innerHeight - rect.height - 8);
  const left = Math.min(event.clientX, innerWidth - rect.width - 8);
  ui.menu.style.top = `${Math.max(8, top)}px`;
  ui.menu.style.left = `${Math.max(8, left)}px`;
}

function closeUserMenu() {
  ui.menu.hidden = true;
}

function renderTopic() {
  const topic = topics.get(active);
  ui.topic.textContent = topic || '';
  ui.topic.hidden = !topic;
}

// -------------------------------------------------------------------- input

/**
 * Every command the input understands, declared once.
 *
 * The dispatcher below and the completion menu both read this, so a command
 * cannot exist without being offered, and cannot be offered without working.
 * Keeping them apart is how a client ends up suggesting something it does not
 * implement.
 */
const COMMANDS = [
  {
    name: 'join',
    args: '#channel',
    help: 'join a channel',
    run: ({ argument }) => send({ type: 'join', channel: argument }),
  },
  {
    name: 'part',
    args: '[#channel]',
    help: 'leave a channel, or this one',
    run: ({ argument }) => send({ type: 'part', channel: argument || active }),
  },
  {
    name: 'msg',
    args: '<nick> <text>',
    help: 'send someone a private message',
    run: ({ rest }) => {
      const [target, ...words] = rest;
      const text = words.join(' ');
      if (!target || !text) {
        append(STATUS_BUFFER, 'error', null, 'usage: /msg <nick> <text>');
        return;
      }
      if (!isChannelName(target)) { openQuery(target); select(target); }
      send({ type: 'message', target, text });
      append(target, 'self', myNick, text);
    },
  },
  {
    name: 'query',
    args: '<nick>',
    help: 'open a conversation without sending anything yet',
    run: ({ rest }) => {
      if (!rest[0]) {
        append(STATUS_BUFFER, 'error', null, 'usage: /query <nick>');
        return;
      }
      openQuery(rest[0]);
      select(rest[0]);
    },
  },
  {
    name: 'whois',
    args: '[nick]',
    help: 'look someone up',
    run: ({ argument }) => {
      send({ type: 'raw', line: `WHOIS ${argument || active}` });
      select(STATUS_BUFFER);
    },
  },
  {
    name: 'me',
    args: '<does something>',
    help: 'send an action',
    run: ({ argument }) => {
      if (!isChannel(active)) {
        append(STATUS_BUFFER, 'error', null, 'pick a channel or a person first');
        return;
      }
      if (!argument) {
        append(STATUS_BUFFER, 'error', null, 'usage: /me <does something>');
        return;
      }
      // An action is an ordinary PRIVMSG wrapped in CTCP markers, so it goes
      // the normal way and gets the same flood protection as anything else.
      send({ type: 'message', target: active, text: `${CTCP}ACTION ${argument}${CTCP}` });
      append(active, 'action', myNick, argument);
    },
  },
  {
    name: 'nick',
    args: '<newnick>',
    help: 'change your nick',
    run: ({ rest }) => {
      if (!rest[0]) {
        append(STATUS_BUFFER, 'error', null, 'usage: /nick <newnick>');
        return;
      }
      send({ type: 'raw', line: `NICK ${rest[0]}` });
    },
  },
  {
    name: 'topic',
    args: '[text]',
    help: "set this channel's topic, or show it",
    run: ({ argument }) => {
      if (!isChannelName(active)) {
        append(STATUS_BUFFER, 'error', null, 'a topic belongs to a channel');
        return;
      }
      // No argument asks the server rather than clearing it. Clearing a topic
      // by accident is not something to make easy.
      send({ type: 'raw', line: argument ? `TOPIC ${active} :${argument}` : `TOPIC ${active}` });
    },
  },
  {
    name: 'list',
    args: '[pattern]',
    help: 'list channels, busiest first',
    run: ({ argument }) => {
      send({ type: 'raw', line: argument ? `LIST ${argument}` : 'LIST' });
      select(STATUS_BUFFER);
    },
  },
  {
    name: 'raw',
    args: '<line>',
    help: 'send a line to the server exactly as typed',
    run: ({ argument }) => send({ type: 'raw', line: argument }),
  },
];

const COMMANDS_BY_NAME = new Map(COMMANDS.map((c) => [c.name, c]));

function say(event) {
  event.preventDefault();
  hideCompletions();
  const text = ui.input.value.trim();
  if (!text) { return; }
  ui.input.value = '';

  if (text.startsWith('/')) {
    const [word, ...rest] = text.slice(1).split(/\s+/);
    const command = COMMANDS_BY_NAME.get(word.toLowerCase());
    if (!command) {
      append(STATUS_BUFFER, 'error', null, `unknown command /${word}`);
      return;
    }
    command.run({ argument: rest.join(' '), rest });
    return;
  }

  if (active === WIRE_BUFFER) {
    send({ type: 'raw', line: text });
    return;
  }
  if (!isChannel(active)) {
    append(STATUS_BUFFER, 'error', null, 'pick a channel or a person first, or use /msg');
    return;
  }
  send({ type: 'message', target: active, text });
}

// -------------------------------------------------------------- completions

let completions = [];
let highlighted = 0;

/**
 * What is being completed, or null when nothing is.
 *
 * Two sources, chosen by where the caret is rather than by a mode: a slash at
 * the very start means a command, and any other word means whoever is in the
 * channel. `start` and `end` bound the word so accepting can replace exactly it
 * and leave the rest of the line alone.
 */
function completionContext() {
  const value = ui.input.value;
  const caret = ui.input.selectionStart ?? value.length;
  const space = value.indexOf(' ');

  if (value.startsWith('/') && (space === -1 || caret <= space)) {
    const end = space === -1 ? value.length : space;
    return { kind: 'command', word: value.slice(1, end).toLowerCase(), start: 0, end };
  }

  const start = value.lastIndexOf(' ', caret - 1) + 1;
  const word = value.slice(start, caret);
  return word ? { kind: 'nick', word: word.toLowerCase(), start, end: caret } : null;
}

function candidates(context) {
  if (context.kind === 'command') {
    return COMMANDS
      .filter((command) => command.name.startsWith(context.word))
      .map((command) => ({
        word: command.name,
        name: `/${command.name}`,
        args: command.args,
        help: command.help,
        // The trailing space is the point: the next thing typed is an argument.
        insert: `/${command.name} `,
      }));
  }

  return (memberLists.get(active) || [])
    // Completing your own nick is not something anyone wants.
    .filter((member) => member.nick !== myNick
        && member.nick.toLowerCase().startsWith(context.word))
    .map((member) => ({
      word: member.nick.toLowerCase(),
      name: member.nick,
      args: member.prefix || '',
      help: member.operator ? 'operator' : '',
      // Addressing someone at the start of a line is written "nick: " by
      // convention, and clients highlight on it.
      insert: context.start === 0 ? `${member.nick}: ` : `${member.nick} `,
    }));
}

function refreshCompletions() {
  const context = completionContext();
  if (!context) { hideCompletions(); return; }

  completions = candidates(context);
  // A word that is already the only thing it could be has nothing left to
  // suggest, and a menu restating it would just sit in the way.
  if (!completions.length
      || (completions.length === 1 && completions[0].word === context.word)) {
    hideCompletions();
    return;
  }

  highlighted = 0;
  renderCompletions();
}

function renderCompletions() {
  ui.completions.replaceChildren(...completions.map((candidate, index) => {
    const item = document.createElement('li');
    item.className = 'completion' + (index === highlighted ? ' on' : '');

    for (const [className, text] of
        [['c-name', candidate.name], ['c-args', candidate.args], ['c-help', candidate.help]]) {
      const span = document.createElement('span');
      span.className = className;
      span.textContent = text;
      item.append(span);
    }

    // mousedown, not click: the input blurs first on a click and the menu would
    // already be gone by the time the handler ran.
    item.addEventListener('mousedown', (event) => {
      event.preventDefault();
      accept(candidate);
    });
    return item;
  }));
  ui.completions.hidden = false;
}

function hideCompletions() {
  completions = [];
  ui.completions.hidden = true;
  ui.completions.replaceChildren();
}

function move(by) {
  highlighted = (highlighted + by + completions.length) % completions.length;
  renderCompletions();
}

/** Replaces the word being completed, leaving everything around it alone. */
function accept(candidate) {
  const context = completionContext();
  if (!context) { hideCompletions(); return; }

  const value = ui.input.value;
  // The insert ends in a space; if one is already there, do not leave two.
  const after = value.slice(context.end).replace(/^ /, '');
  ui.input.value = value.slice(0, context.start) + candidate.insert + after;

  const caret = context.start + candidate.insert.length;
  ui.input.setSelectionRange(caret, caret);
  ui.input.focus();
  hideCompletions();
}

function onInputKey(event) {
  if (ui.completions.hidden) { return; }

  switch (event.key) {
    case 'ArrowDown': event.preventDefault(); move(1); return;
    case 'ArrowUp': event.preventDefault(); move(-1); return;
    case 'Escape': event.preventDefault(); hideCompletions(); return;
    case 'Tab':
      event.preventDefault();
      accept(completions[highlighted]);
      return;
    case 'Enter': {
      // Only ever for a command, and only one not yet typed in full. Taking
      // Enter while someone is writing a message would mean "hey ali" and a
      // return sent "hey alice" instead of what was typed and read back.
      const context = completionContext();
      if (context && context.kind === 'command' && !COMMANDS_BY_NAME.has(context.word)) {
        event.preventDefault();
        accept(completions[highlighted]);
      }
      return;
    }
    default:
  }
}

function splitChannels(value) {
  return value.split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);
}

// Attribute selectors choke on '#', which every channel name starts with.
function cssEscape(value) {
  return window.CSS && CSS.escape ? CSS.escape(value) : value.replace(/#/g, '\\#');
}

// --------------------------------------------------------------------- wiring

ui.input.addEventListener('input', refreshCompletions);
ui.input.addEventListener('keydown', onInputKey);
// Arrow keys and clicks move the caret without firing input.
ui.input.addEventListener('click', refreshCompletions);
ui.input.addEventListener('blur', hideCompletions);

$('connect').addEventListener('submit', connect);
ui.stop.addEventListener('click', disconnect);
ui.server.addEventListener('change', () => { showNotes(); loadProfile(); });
ui.saveProfile.addEventListener('click', saveProfile);
$('add-server').addEventListener('click', () => openServerDialog(null));
$('edit-server').addEventListener('click',
  () => openServerDialog(servers.find((s) => s.id === ui.server.value)));
$('delete-server').addEventListener('click', deleteServer);
$('server-save').addEventListener('click', saveServer);
$('server-cancel').addEventListener('click', () => ui.serverDialog.close());
$('change-password').addEventListener('click', () => {
  $('password-error').textContent = '';
  ui.passwordDialog.showModal();
});
$('password-save').addEventListener('click', changePassword);
$('password-cancel').addEventListener('click', () => ui.passwordDialog.close());
$('say').addEventListener('submit', say);
document.addEventListener('click', closeUserMenu);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeUserMenu(); } });
// The pinned buffers exist before anything connects, so there is somewhere for
// early status and wire lines to land.
renderTabs([]);
select(STATUS_BUFFER);

loadMe();
loadServers();
openSocket();
