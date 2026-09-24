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
