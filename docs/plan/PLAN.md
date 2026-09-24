# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse
than no plan.

---

## Now

### Credential safety — done
Tasks 01, 02 and 03 have all landed: unusable credentials are rejected on
save, a connect failure stops the bot it built instead of orphaning it (with
a pre-connect credential check and a fixed, cause-typed reason to the
browser), and app.js shows those save- and connect-failure reasons by the
form. This closes the incident where a bad Twitch password left an orphaned
bot reconnecting every 60s for hours (see HISTORY).

### Take irc-client 1.2.x — done
irc-web runs on 1.2.1 (pom.xml pin moved from 1.1.0; 1.2.0 was never
published). `ServerRefusedException` and the credential builder's
`IllegalArgumentException` map by type to fixed, credential-free reasons. A
failed registration leads with the server's own scrubbed NOTICE/ERROR text
ahead of that fixed reason. The browser now shows `reconnecting` (warn-
coloured, input and Say disabled, Stop enabled, attempt/delay in the detail
line) driven live off irc-client's `onDisconnected`/`onReconnecting` events,
and `gave up` (Connect re-enabled) off `onGaveUp`, wired end to end through
`IrcSession`/`IrcSessionRegistry`. Production retries for about 2 hours (1s
initial, 300s max, 32 attempts) before giving up — see HISTORY.
**Later, small:** `CredentialRules`' javadoc still says "mirroring irc-client
1.1.0"; needs a one-line update to 1.2.1.

(Session-expiry — the socket dying silently 30 minutes after page load — is
fixed; see HISTORY.)

## Next

### Harden the connect-failure path further
Three small gaps parked by task 02's review, none blocking: (a) tests assert
`running` resets on a failed connect but never that `stop()` was actually
called on the built bot; (b) an `SSLHandshakeException` arriving as a direct
connect error (not wrapped) is not mapped to the TLS-handshake reason; (c)
`IrcSession.stopBuilt` only catches `RuntimeException` around `stop()`, so an
`Error` thrown there would replace the original failure instead of being
swallowed alongside it.

### Theme switcher
The blue branding (tasks 01-02) has landed, fixed and non-switchable. The theme
pack at /srv/dev/scratch/irc-web-theme-pack (not in the repo; the maintainer
holds the zip) has five more palettes — phosphor, amber, iris, coral,
paper-teal — meant to drive the whole UI, not just the logo. Needs per-palette
text, line and good/bad/warn values the pack does not supply; a contrast pass
(paper-teal is light, unlike the rest); removing the 22 hardcoded colours
currently in style.css and login.css; and a picker.

### Persist the backlog
Session history is in memory, capped at 500 messages, and a restart loses it
along with the connections. Needs an H2 table, a retention policy, and a
decision about private messages sitting on disk — the stored IRC passwords are
encrypted at rest and message history arguably should be too.

### More than one network at once
A session is keyed by account, so connecting to a second network is refused.
Lifting that needs a window that can show several, not just a registry change.

## Someday

### Websocket send is synchronous under sendLock on the event thread
Known since task 03: a stalled browser socket delays IRC connection events
(including reconnecting/gave-up status) because the send happens under
`sendLock` on irc-client's connection-event thread. Not observed as a
problem yet; worth an async send queue if it ever is.

### Attach a real IRC client
A listener port so HexChat, irssi or a phone client can attach to the same
session alongside the browser — a ZNC replacement rather than a web client with
a bouncer behind it. Means implementing the server half of IRC, authenticating
clients, replaying state to each, and fanning traffic both ways. Largest of
these by far, and it opens a credentialed port.
