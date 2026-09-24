# History

What was built, newest first. Append-only. Only `scribe` edits this file.

**Read this before redesigning anything.** The `Cost` line on each entry is the
point of the file — it is where the sessions that already tried the obvious
approach tell you what happened.

Do not load this file to find out whether something exists. It grows without
bound. `HISTORY-INDEX.md` carries one row per entry; scan that, then grep this
file for the exact heading it names. Every entry added here gets its index row
in the same commit.

<!--
## YYYY-MM-DD — <what landed>
<Two or three sentences: what it does now that it did not before.>
**Cost:** <what was hard, what was tried and abandoned, what not to retry.>
-->

## 2026-09-24 — irc-client 1.2.1 is live, and the UI has a reconnecting state

irc-web now pins irc-client 1.2.1 (1.2.0 was dropped unpublished on the
Central Portal). `ServerRefusedException` and the credential builder's
`IllegalArgumentException` both map by exception type to the same fixed,
credential-free reasons the browser already showed — no message text from
irc-client reaches the client. A test-only, package-private reconnect-backoff
seam was added to `IrcSession` so tests can shrink the wait between attempts;
production's reconnect defaults are untouched. A test now proves
`Forwarder.onReady` still runs correctly after a reconnect, which on 1.2.1
arrives on irc-client's own `irc-connection-events` thread rather than the
caller's. Separately, app.js now treats a `reconnecting` status the way it
already treats `connecting`: the message input and Say button disabled, Stop
enabled, the status line coloured with `var(--warn)`; Connect stays enabled
throughout and is usable again once a `gave up` disconnect arrives. Sending is
deliberately disabled while reconnecting rather than queued — a queued line
could land minutes later in a channel the bot has not rejoined yet, or be
lost silently if it gives up. Shared JS test scaffolding for socket/status
tests now lives in `src/test/js/helpers.mjs`.

This lands the version bump and the UI half of the plan noted when 1.2.1 was
still pending (see PLAN.md). The two events that actually drive the new UI
state — irc-client's `onDisconnected`/`onReconnecting`/`onGaveUp` — are not
wired up yet; that is task 03, blocked on task 02 (showing the server's own
NOTICE/ERROR text on a failed registration), both still open.

**Cost:** none on the implementation side for either piece; both passed
review on the first round with no rework. One gap fell out of reviewing the
1.2.1 credential mapping: the nick, when reused as the SASL username,
is never checked against the SASL-username rule, so a nick like `a b` fails
inside irc-client with a message pointing at the wrong field. Folded into
task 02 rather than fixed here since it touches the same `IrcSession`
validation this task already changed. `CredentialRules`' javadoc still
says "mirroring irc-client 1.1.0" — cosmetic, left for whichever of 02/03
touches that file next.

## 2026-09-24 — A failed connect now stops the bot instead of orphaning it

Closes the incident this whole credential-safety line of work was for: a bad
Twitch password left an orphaned bot reconnecting every 60s for hours,
leaking the token into the container log on each attempt. `IrcSession.connect`
now checks the effective `password`, `saslPassword` and `saslUsername`
against `CredentialRules` before opening a socket, so a stored-bad or
typed-bad credential is refused before a bot is even built. Every failure
path after the bot is built — any `Throwable`, not just the exceptions
irc-client 1.1.0 is documented to throw — calls `stop()` on that bot, held in
a local variable, before the exception leaves `connect`; `IrcWebSocketHandler`'s
connect `catch` was widened so `registry.end` runs even for an `Error`. The
reason reaching the browser is chosen by the cause's *type*, from a fixed
small set (credential/config refused, TLS handshake failed, server
unreachable, generic fallback), never by its message, and the exception
rethrown to the handler carries no cause — so nothing credential-bearing can
reach a log or an outbound event on this path.

**Cost:** none on the implementation side — clean pass first review round.
The review did surface three small gaps, parked rather than fixed: tests
check `running` resets on failure but never assert `stop()` was actually
invoked on the failed bot; a direct (unwrapped) `SSLHandshakeException` from
connect is not mapped to the TLS reason, only one arriving as a cause is;
and `stopBuilt` only catches `RuntimeException` around `stop()`, so an
`Error` there would replace the original failure instead of being logged
alongside it. See PLAN.md "Harden the connect-failure path further".

## 2026-09-24 — Bad credentials are now rejected on save and their errors shown by the form

Prompted by an incident: a Twitch password saved as the whole `PASS
oauth:…` line made irc-client reject it at registration; irc-web's
`IrcSession` caught the failure and set `bot = null` without calling
`stop()`, orphaning the bot, whose own reconnect loop then ran every 60s for
hours; irc-client 1.1.0's exception message wrote the token into the
container log on each attempt, and the log was only cleared by recreating
the container. Two fixes have landed from that: `CredentialRules`, checked
in `DirectoryService.saveProfile` before encryption and persistence, now
rejects a server password containing whitespace, CR, LF, NUL or a leading
`:` (with a case-insensitive hint if it looks like a whole `PASS` line), a
SASL username containing whitespace or a leading `:`, and a SASL password
containing CR, LF or NUL — with a fixed 400 message that never echoes the
value. And `app.js` now shows the save-failure and connect-failure reasons
next to the form in `#profile-state` (aria-live, `textContent` only), clearing
them on a successful save, a server switch, `connecting` or `ready`, while
password fields keep what was typed but are never echoed.

**Cost:** the first attempt at `CredentialRules` rejected spaces in SASL
passwords because the coordinator's spec was wrong; review caught it by
checking irc-client's `Authenticate.plain`, which allows spaces in SASL
PLAIN. The library-side leak (writing the raw token into exception messages)
is fixed in irc-client 1.2.1, not yet released — irc-web still pins 1.1.0.
The orphaned-bot half of the incident (`stop()` never called, 60s reconnect
loop) is not fixed by this work; that's task 02, still open, and it's next
because it's the part that actually leaves a bot running unattended.

## 2026-09-24 — Brand assets and manifest now serve without login or setup

`/brand/**` and `/manifest.webmanifest` are permitted in `SecurityConfig` and
let through `SetupRequiredFilter` before first-run setup, so browsers can
fetch favicons and the manifest with no cookies and before anyone is signed
in — closing the gap task 01 left open. Everything else stays behind auth:
`/index.html`, `/app.js` and `/api/servers` are unreachable both before setup
(409) and after (401), unauthenticated. New `BrandingIT` (own fresh database,
`FirstRunIT`'s ordered before/after-setup pattern) pins both states plus
raw-socket path-traversal attempts against `/brand/..;/index.html`,
`/brand/%2e%2e/app.js` and `/brand/../api/servers`, none of which return 200.

**Cost:** `SetupRequiredFilter`'s prefix check first matched on the raw
request URI; a security review pointed out that makes the check depend on
Tomcat/Spring Security's URI normalization rather than being self-contained,
so it now matches on `getServletPath()+getPathInfo()` instead. The review
also confirmed *why* the traversal probes return 400 rather than reaching the
filter at all: `StrictHttpFirewall`, ahead of this filter in Spring
Security's chain, rejects `..`, `;`, `%2e` and `//` outright — the IT now
proves that boundary explicitly rather than assuming it. Ergo's throttle hit
again on the first local verify; `docker restart irc-web-ergo` cleared it, as
before — restart only that container, never `irc-web` itself, and check
nothing else is mid-build against the same ergo first.

## 2026-09-24 — Fixed-blue branding: favicons, manifest, login/setup logo

irc-web now ships a brand identity instead of Spring Boot defaults: an inline
SVG logo (role="img") on the login and setup pages, an aria-hidden `#.` mark
in the app header, and matching favicon/apple-touch-icon/icon-192/512 assets
under static/brand/, byte-identical to the reviewed theme pack's `themes/blue`.
All three pages carry the same icon, theme-color and manifest tags.
`/manifest.webmanifest` serves standalone display, `#12141A` background, and
"any maskable" icons verified opaque with the mark inside the safe zone;
`server.mime-mappings.webmanifest=application/manifest+json` was needed
because Tomcat has no default mapping for that extension. SVG fills use
`--irc-brand-mark`/`-dot`/`-wordmark` (blue fallbacks), with `--irc-brand-mark`
aliased to `--accent` so a future theme switcher only changes variables.

**Cost:** Review caught two things worth remembering: the header symbol was
announcing "irc-web" twice to screen readers before it was made
`aria-hidden`, and the first pass hardcoded fills instead of using the pack's
variables. Separately, before task 02 lands, the favicon and manifest are
still redirected to login for an unauthenticated visitor — known gap, not a
regression. The implementer's first `spring-boot:run` collided with the live
homelab instance on 8667; dev runs must pass `--server.port=808x`.

## 2026-09-24 — Fixed silent session expiry, moved the default port to 8667

The SPA talks only over its WebSocket after load, and socket traffic never
refreshed the HTTP session, so 30 minutes after page load the server closed
the socket with 1008. The reopen then got a 302, which the browser's WebSocket
API reports only as 1006, so `app.js` retried every 15 seconds forever with no
login prompt and no visible error. Confirmed live before the fix.

Fixed on both ends. Server: Spring Security persistent-token remember-me
(`JdbcTokenRepositoryImpl` on H2, 30 days, key an HMAC of a label under
`SecretCodec`'s key) lets a reopen carrying the remember-me cookie
authenticate without a session; `/ws/**` now answers 401 instead of
redirecting to login, so a browser that is not authenticated gets an error it
can act on; logout deletes the token even when the request is authenticated
only by the cookie, after a constant-time check against the stored row.
Client: `app.js` pings `/api/me` every 5 minutes while the socket is open, and
probes `/api/me` before reopening — a 401 goes to login, a network error backs
off and retries. The login page grew a "Remember me" checkbox, checked by
default, so this is the default behaviour without the user doing anything.

Separately, the default port moved from 8081 to 8667 so a deployed instance
does not collide with the many other things already using 808x; the dev
compose stack and local runs stay on 8081. `data/` (the H2 file and the
encryption key) is now gitignored instead of tracked.

**Cost:** The IRC integration tests all share one ergo container. Concurrent
irc-web builds, and even repeated serial runs in the same session, trip
ergo's connection throttle ("connected too many times"), which then persists
for minutes; `docker restart irc-web-ergo` clears it. Do not run irc-web ITs
in parallel — this cost two attempts before it was written down.
`docker restart` must target `irc-web-ergo`, never the `irc-web` container
itself, which is a live homelab deployment. The port-move task's first
attempt also missed a hardcoded 8081 health-check probe in the `docker.yml`
CI workflow, caught only because CI failed after merge would have been the
alternative — check every hardcoded port reference, not just the obvious
config files, when moving a default port.
