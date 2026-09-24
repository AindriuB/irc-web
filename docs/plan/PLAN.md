# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse
than no plan.

---

## Now

### Take irc-client 1.2.0
Carries the TLS-refusal message fix (#39), the reconnect-throttle fix plus
`ServerRefusedException` (#40), and whatever bot-facade additions land in that
release. 1.2.0 will also bring connection-state events (`onDisconnected`,
`onReconnecting`, `onGaveUp`) that irc-web should surface as a "reconnecting…"
status in the UI — that needs its own task once 1.2.0 ships, not part of this
one.
**Blocked by:** irc-client 1.2.0 not yet released (irc-web pins 1.1.0 in
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
