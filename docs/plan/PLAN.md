# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse
than no plan.

---

## Now

### Credential safety
Task 01 (reject unusable credentials on save) and task 03 (show credential
and connect errors by the form) have landed. Task 02 (task/02, stop the bot
on connect failure instead of orphaning it, plus pre-connect credential
checks) is next and matters most of what's open here: it's the fix for the
incident where a bad Twitch password left an orphaned bot reconnecting every
60s for hours (see HISTORY).

### Take irc-client 1.2.x
1.2.0 (TLS-refusal message fix #39, reconnect-throttle fix plus
`ServerRefusedException` #40) is staged on the Central Portal awaiting the
maintainer's Publish click. 1.2.1, which fixes the exception-message secret
leak that caused the Twitch-password incident (see HISTORY), is ready to cut
behind it. irc-web should take 1.2.1, not 1.2.0, once it's out — 1.2.0 alone
still carries the leak. 1.2.x also brings connection-state events
(`onDisconnected`, `onReconnecting`, `onGaveUp`) that irc-web should surface
as a "reconnecting…" status in the UI — that needs its own task once it
ships, not part of this one.
**Blocked by:** irc-client 1.2.1 not yet released (irc-web pins 1.1.0 in
pom.xml).

(Session-expiry — the socket dying silently 30 minutes after page load — is
fixed; see HISTORY.)

## Next

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
