// Shared jsdom scaffolding for driving app.js from a fake socket and fetch:
// the "real page" every test file bootstraps before poking at the DOM.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

export const STATIC = new URL('../../main/resources/static/', import.meta.url);
export const source = readFileSync(new URL('app.js', STATIC), 'utf8');

export const SERVER = {
  id: 'srv1', name: 'Test', host: 'irc.example', port: 6697,
  tls: true, sasl: false, registered: false, features: [], notes: '',
};

/** A fake WebSocket that records every instance created and lets the test drive it. */
export function fakeSocketClass(sockets) {
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

/** A GET/PUT-aware fetch stub: servers, an (empty) stored profile, and a PUT outcome. */
export function fakeFetch({ putResult } = {}) {
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
    if (path.endsWith('/profile') && method === 'PUT') {
      return putResult ? putResult() : { ok: true, status: 200, json: async () => ({}) };
    }
    return { ok: true, status: 200, json: async () => ({}) };
  };
}

/** Loads index.html into jsdom with a fake socket and the given fetch stub wired in. */
export function page(fetchImpl) {
  const dom = new JSDOM(readFileSync(new URL('index.html', STATIC), 'utf8'),
      { runScripts: 'outside-only', url: 'http://localhost/' });
  const { window } = dom;

  const sockets = [];
  window.fetch = fetchImpl;
  window.WebSocket = fakeSocketClass(sockets);
  window.eval(source);

  return { window, document: window.document, sockets };
}

/**
 * Timer callbacks that go on to `await fetch(...)` need the microtask queue
 * drained after `tick()`, which only runs the timer callback itself. `setImmediate`
 * is real (only `setInterval`/`setTimeout` are mocked), so it is a way to let
 * those microtasks settle before assertions run.
 */
export function flush() {
  return new Promise((resolve) => { setImmediate(resolve); });
}

/** Several microtask hops: server load, then profile load, both chained without awaits. */
export async function settle() {
  await flush(); await flush(); await flush(); await flush();
}
