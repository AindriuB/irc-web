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

### Take irc-client 1.2.x
1.2.0 was dropped unpublished on the Central Portal. 1.2.1 — which folds in
1.2.0's fixes and adds the exception-message secret-leak fix that caused the
Twitch-password incident (see HISTORY) — is staged and awaiting the
maintainer's Publish click. irc-web moves to 1.2.1 once it's out. 1.2.1 also
brings connection-state events (`onDisconnected`, `onReconnecting`,
`onGaveUp`); once irc-web is on it, plan a "reconnecting…" status in the UI
driven by those events — a new task, not part of the version bump.
**Blocked by:** irc-client 1.2.1 not yet released (irc-web pins 1.1.0 in
pom.xml).

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

### Attach a real IRC client
A listener port so HexChat, irssi or a phone client can attach to the same
session alongside the browser — a ZNC replacement rather than a web client with
a bouncer behind it. Means implementing the server half of IRC, authenticating
clients, replaying state to each, and fanning traffic both ways. Largest of
these by far, and it opens a credentialed port.
