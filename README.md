# irc-web

[![build](https://github.com/AindriuB/irc-web/actions/workflows/build.yml/badge.svg)](https://github.com/AindriuB/irc-web/actions/workflows/build.yml)

A Spring Boot web front end for [irc-client](https://github.com/AindriuB/irc-client),
and the exercise that proves the library works outside its own test suite.

Its own tests are unit tests: they drive the library through an `EmbeddedChannel` or
a stub server, in one process, one connection at a time. This puts it where a real
application does — many concurrent connections, events arriving on Netty threads and
being fanned out to browsers, and connections that come and go as people reload a tab.

## Running it

Everything in containers:

```bash
docker compose -f docker/compose.yaml up -d --build
# http://localhost:8081
```

Or the app on the host against a containerised IRC server, which is the better
loop while changing the app:

```bash
docker compose -f docker/compose.yaml up -d ergo
mvn spring-boot:run
```

`local` means a different address in each case — loopback on the host, the compose
service name inside the network — so `servers.yml` takes it from `LOCAL_IRC_HOST`,
which the compose file sets. Loopback inside the app container is the app container,
which runs no IRC server, and the failure looks like a connection refused with no
obvious cause.

Published images are at `ghcr.io/aindriub/irc-web`.

Pick a server, choose a nick, connect. `/join #chan`, `/part`, `/msg nick text` and
`/raw <line>` all work; **wire traffic** in the top right shows every line the server
sends, which is how you tell a parsing bug from an application bug.

## The server directory

`src/main/resources/config/servers.yml` lists what to test against, and why each one
is worth testing against. It is data rather than code so that adding a network is an
edit to a list.

| | Exercises |
| --- | --- |
| **local** / **local-tls** | registration, channels, modes, reconnect, TLS, SASL |
| **Libera.Chat** | the best-behaved public network; SASL and ISUPPORT |
| **OFTC** | a different ircd, so implementation-specific assumptions show up |
| **IRCnet** | no services, no SASL — proves nothing depends on modern conveniences |
| **EFnet** | no nick ownership, so collisions are ordinary; exercises nick retry |
| **QuakeNet** | pings *during* registration; `PREFIX=(ov)@+`, `CASEMAPPING=rfc1459` |
| **Rizon** | different `CHANMODES`/`PREFIX`, proving the mode parser reads ISUPPORT |
| **Twitch** | IRCv3 tags, capabilities and real rate limits |

Start with **local**. A test that needs the internet fails for reasons that have
nothing to do with the code, and the local server is the only one that can be
restarted mid-test — which makes it the only one that can exercise reconnection
honestly:

```bash
docker compose -f docker/compose.yaml restart ergo
```

The browser should show the connection drop, reconnect with backoff, re-register and
rejoin its channels without being touched.

### Using someone else's server

These are real networks run by volunteers.

- Use a distinctive nick, not `bot` or `test`.
- Test in `##test`, a channel you created, or a network's own test channel — never in
  a project's support channel.
- Leave flood protection on. The defaults sit well inside every network's limits, and
  the one time it matters you will not get a warning first.
- Point reconnect loops at the local container, not at a public network.

## How it fits together

```
browser ──WebSocket(JSON)──▶ IrcWebSocketHandler ──▶ IrcSession ──▶ IRCBot ──▶ server
        ◀───────────────────                      ◀── BotListener ◀──
```

One browser socket, one IRC connection, held together by two threading rules:

- **A `WebSocketSession` is not safe for concurrent use**, and IRC events arrive on
  whichever Netty thread was reading. Every send is serialised on a per-session lock.
- **Nothing that talks to IRC runs on a container thread.** `IRCBot.start()` blocks
  until registration completes, and a send can block on flood protection. Both go to
  a worker pool.

Plain WebSocket rather than STOMP: the browser needs one socket carrying a handful of
message types, and STOMP would add a broker, a client library and a subscription model
to say the same thing. It also keeps the front end dependency-free — no CDN, no build
step, just three files in `static/`.

## Tests

```bash
mvn verify                                     # needs the local server running
```

Note `verify`, not `test`. The tests are named `*IT`, which surefire does not pick
up — `mvn test` ran nothing at all until failsafe was wired in, and reported success
while doing it. CI asserts both that the IRC server is reachable *and* that a non-zero
number of integration tests ran, because a suite that silently runs nothing is worse
than no suite.

`LocalIrcServerIT` drives the whole stack against ergo: registration, joining and
member lists, a message travelling between two browser sessions through the IRC
server, and raw lines reaching the browser. It **skips** rather than fails when the
container is not running — worth remembering that a skipped test has proved nothing.

## Signing in

On first start an account is created. Set `IRC_WEB_ADMIN_USERNAME` and
`IRC_WEB_ADMIN_PASSWORD` to choose it; leave them unset and a password is generated
and printed to the log **once**:

```bash
docker compose -f docker/compose.yaml logs irc-web | grep -A4 'has been generated'
```

The header nags until a generated password is changed, because one that was printed
once and never changed is how these end up effectively unauthenticated.

## What is stored, and how

The directory and a profile per server live in an H2 file under the data directory:
nick, channels, SASL account, and the two passwords. `servers.yml` **seeds an empty
database** and is then left alone — re-reading it on every start would silently undo
every edit made in the UI.

Passwords are encrypted at rest with AES-GCM. The key comes from
`IRC_WEB_SECRET_KEY`, or is generated into the data directory on first start so the
default path still encrypts rather than quietly storing plaintext. Back it up with
the database; without it the stored passwords cannot be read back, which is an
inconvenience rather than a loss since they can be entered again.

**The API never returns a stored password** — only whether one is set. This
application holds credentials for other people's networks, and a readable-back
password is one forgotten firewall rule from being someone else's. An untouched
password field leaves what is stored alone; an empty one clears it.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

Still worth saying plainly: this is a test harness. Anyone who signs in can connect
to any network in the directory and send raw IRC commands from your address. Keep it
on a network you trust.

## Depending on irc-client

```xml
<dependency>
    <groupId>io.github.aindriub</groupId>
    <artifactId>irc-client</artifactId>
    <version>1.0.0</version>
</dependency>
```
