# 03 — Report reconnecting and giving up to the browser

**Repo:** .
**Depends on:** 02
**Owns:**
- src/main/java/io/github/aindriub/ircweb/irc/IrcSession.java
- src/main/java/io/github/aindriub/ircweb/irc/LiveSession.java
- src/main/java/io/github/aindriub/ircweb/irc/IrcSessionRegistry.java
- src/test/java/io/github/aindriub/ircweb/irc/IrcSessionReconnectTest.java (new)
- src/test/java/io/github/aindriub/ircweb/irc/IrcSessionRegistryTest.java (new)

## Goal
Forward irc-client 1.2.1's connection events to the browser as `status`
events. A drop shows as "reconnecting", a successful reconnect goes back to
"ready", and giving up ends the session so the user can connect again. Today
a dropped connection retries silently forever.

## Context
- BotListener (irc-client 1.2.1) — `onDisconnected(bot)`,
  `onReconnecting(bot, attempt, delayMillis)`, `onGaveUp(bot, attempts)`. All
  run on the client's connection-event thread and must not block. `onGaveUp`
  fires only if `maxAttempts` is finite, and the library default is 0 (never)
- IrcSession.java `Forwarder` (after task 01/02) — add the overrides here.
  Keep `server.name()` from `connect` in a field for the details
- The reconnect-backoff test seam added by task 01
- LiveSession.java:52-56, 74-105, 151-158 — `record`, and `close()`, which
  clears the watcher before disconnecting
- IrcSessionRegistry.java:43-60 — `open`/`end`. `end` calls
  `LiveSession.close()` → `IrcSession.disconnect()` → `IRCBot.stop()`
- docs/architecture.md "Boundaries" 1-3 — sends are serialised per socket,
  and a closed socket must never end an IRC session

## Browser contract (task 04 implements the other side; keep these exact)
- `onDisconnected` → `status`, state `reconnecting`, detail
  `lost the connection to <name>`
- `onReconnecting` → `status`, state `reconnecting`, detail
  `reconnecting to <name> (attempt <n>, in <s>s)`, with
  `s = ceil(delayMillis / 1000)`
- `onReady` after a reconnect → the existing `ready` status, unchanged
- `onGaveUp` → `status`, state `disconnected`, detail
  `gave up reconnecting to <name> after <n> attempts`

## Acceptance
- [ ] Production sets a finite reconnect cap through `reconnectBackoff`, as a
      named constant with a one-line comment giving the total wait it implies.
      Delays stay at the library defaults (1 s initial, 60 s max)
- [ ] Each connection event produces exactly the status above. Test with the
      fake server: accept registration, drop, refuse the next attempt(s)
      (short backoff via the seam). Assert the `reconnecting` details in
      order, then accept, and assert a `ready` follows
- [ ] Test: the server never comes back. With a cap of 2 and a short backoff,
      the sink sees the `gave up … after 2 attempts` status, then
      `IrcSession.isRunning()` is false, and a new `connect` on a fresh
      registry session succeeds
- [ ] On gave-up the `LiveSession` is removed from `IrcSessionRegistry`, but
      only if the registry still maps the username to that same instance
      (compare-and-remove). Test in `IrcSessionRegistryTest`: an old session's
      gave-up must not remove a newer session opened under the same username
- [ ] The gave-up status reaches the attached watcher before `close()` clears
      it
- [ ] Nothing blocks the connection-event thread. Stopping the bot after
      gave-up runs off that thread (or is shown by a test not to deadlock).
      Test: the gave-up path completes within 5 s
- [ ] Events from a bot that is no longer this session's current `bot`
      (after `disconnect()` or a failed first connect's `stopBuilt`) are
      dropped. Test: a failed first connect emits no `reconnecting` status after
      its final `disconnected`
- [ ] A deliberate `disconnect()` emits no `reconnecting` or `gave up` status
- [ ] No status detail contains a password, SASL value or raw line. Details
      are built only from `server.name()` and integers
- [ ] `mvn verify` passes with ergo up. ITs are not run in parallel

## Out of scope
- app.js, style.css and the JS tests (task 04)
- OutboundEvent shape: reuse `status(state, detail)`, add no fields
- IrcWebSocketHandler: its `disconnect` command path already calls
  `registry.end`
- Making the reconnect cap user-configurable
- Failure-reason text from the server (task 02)
