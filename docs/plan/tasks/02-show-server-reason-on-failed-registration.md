# 02 — Show the server's own words when registration fails

**Repo:** .
**Depends on:** 01
**Owns:**
- src/main/java/io/github/aindriub/ircweb/irc/IrcSession.java
- src/main/java/io/github/aindriub/ircweb/irc/ServerText.java (new)
- src/test/java/io/github/aindriub/ircweb/irc/ServerTextTest.java (new)
- src/test/java/io/github/aindriub/ircweb/irc/IrcSessionTest.java

## Goal
In production Twitch answered a bad token with
`:tmi.twitch.tv NOTICE * :Login unsuccessful` and closed, and the browser
showed only "The connection closed before registration finished". While a
connect is in progress, remember the last NOTICE or ERROR the server sent
before registration completed, and put its sanitised text in the failure
reason, e.g. `Twitch said: Login unsuccessful`.

## Context
- IrcSession.java:56-133 — `connect`, where the failure reason is built and
  sent as `status("disconnected", reason)`
- IrcSession.java:361-369 — `RawForwarder`, which already sees every raw
  inbound line (on a Netty thread) and is the capture point. Check that
  irc-client's `eventListener` delivers inbound lines only. If it also
  delivers outbound ones, filter them out; our own lines must never be captured
- IrcServer.java — `name()` is the network's display name ("Twitch")
- `ServerRefusedException.getReply()` (irc-client 1.2.1) holds an ERROR's
  trailing text. It may be used for ERROR, but raw capture has to cover NOTICE
  anyway
- IrcSessionTest.java — fake-server pattern (`rejectRegistration`, 257-290)

## Acceptance
- [ ] New `ServerText.sanitise(String)` (package-private is fine) removes
      every C0/C1 control character, which includes mIRC formatting codes,
      collapses runs of whitespace to one space, trims, and caps the result at
      200 characters, ending in `…` when cut. `ServerTextTest` covers each rule,
      null, and blank input
- [ ] Capture starts only once `connect` has passed the credential check,
      stops when registration completes (`onReady`) or the attempt fails, and
      is held in a field safe to write from a Netty thread and read from the
      connecting thread (volatile or atomic)
- [ ] When both were seen, the last `ERROR` wins over the last `NOTICE`.
      Only the trailing text is kept, never the prefix or the whole raw line
- [ ] Test: the fake server sends `:tmi.twitch.tv NOTICE * :Login unsuccessful`
      and then closes. The `disconnected` detail starts with
      `<server name> said: Login unsuccessful` and still contains the fixed
      reason `mapFailureReason` gives for that failure
- [ ] Test: the fake server sends `ERROR :Closing Link: bad password` and
      closes. The detail starts with `<server name> said: Closing Link: bad password`
- [ ] Test: the fake server sends a NOTICE containing `\u0003`, `\u0002`, a
      CR-free 1000-character tail. The detail has no control characters and the
      quoted part is at most 200 characters
- [ ] Test: a connect with a distinctive password (e.g. `sekrit-XYZ`) that the
      fake server rejects produces no sink event whose any field contains
      `sekrit-XYZ`
- [ ] A connect that fails with no NOTICE/ERROR seen gives exactly the
      reason it gave before this task
- [ ] Captured text reaches only the status detail. The existing `LOGGER.info`
      line is unchanged and does not log it
- [ ] Notices after registration still arrive as ordinary `notice` events,
      unchanged

## Out of scope
- Any change to app.js: it already shows a `disconnected` detail next to the
  form when a connect fails (HISTORY, "Bad credentials are now rejected on
  save…")
- Connection events and reconnect status (task 03)
- Filtering "*** Looking up your hostname" style notices by heuristic
- LiveSession, IrcSessionRegistry, IrcWebSocketHandler, OutboundEvent

## Added from review of 01
Two small items, both in files this task already owns (IrcSession.java, IrcSessionTest.java):
- When there is a SASL password but no SASL username, IrcSession uses the nick as the SASL username (~IrcSession.java:135-138), but firstCredentialProblem never checks it. A nick like "a b" makes irc-client's sasl() throw an IAE, and the user is told "A saved credential is not valid", which points at the wrong field. Check the nick with the SASL-username rule when it is used as one, and give a nick-specific fixed message, e.g. "The nick cannot contain spaces or start with ':' when it is also the SASL username". Test it.
- IrcSessionTest's reconnect test (~:278) assumes the second `ready` comes from irc-client's connection-event thread. Record Thread.currentThread().getName() in the sink and assert it is "irc-connection-events".
