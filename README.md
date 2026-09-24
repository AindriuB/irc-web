# irc-web

[![build](https://github.com/AindriuB/irc-web/actions/workflows/build.yml/badge.svg)](https://github.com/AindriuB/irc-web/actions/workflows/build.yml)

An IRC client you run on your own server and use from a browser.

It keeps your networks, nicks, channels and credentials in its own database, so the
client is set up once rather than on every device, and any browser on your network is
a full client with nothing installed.

The connection belongs to your account, not to the tab. Close the browser and you stay
on the network; open it again — from another machine if you like — and it attaches to
the session that was already running and replays what was said while you were gone.
Only asking to disconnect leaves the network.

Built on [irc-client](https://github.com/AindriuB/irc-client), a Java IRC library
from the same author.

## Running it

Everything in containers:

```bash
docker compose -f docker/compose.yaml up -d --build
# http://localhost:8081
```

A deployed instance runs on port 8667. The `docker/compose.yaml` stack above is the
exception: it keeps 8081, part of the 808x range reserved for development, so it
does not collide with a real deployment on the same host.

Or the app on the host against a containerised IRC server, which is the better
loop while changing the app:

```bash
docker compose -f docker/compose.yaml up -d ergo
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

`local` means a different address in each case — loopback on the host, the compose
service name inside the network — so `servers.yml` takes it from `LOCAL_IRC_HOST`,
which the compose file sets. Loopback inside the app container is the app container,
which runs no IRC server, and the failure looks like a connection refused with no
obvious cause.

Published images are at `ghcr.io/aindriub/irc-web`.

The first page you get is setup: choose a username and a password, and that account
is the one everything else lives behind. Then pick a network, choose a nick, and
connect.

Typing `/` in the message box lists the commands — `/join`, `/part`, `/msg`,
`/query`, `/whois`, `/me`, `/nick`, `/topic`, `/list` and `/raw` — with their
arguments and what they do. Typing anything else completes **nicks** from the
channel, addressed as `nick: ` at the start of a line and plain in the middle of a
sentence. **Tab** completes, the arrows move through the list, **Esc** dismisses it.

Enter completes an unfinished *command*, and never a nick: taking Enter mid-sentence
would mean typing `hey al` and sending `hey alice`.

`/list` sorts by how many people are in each channel and shows the hundred busiest,
because a network's own order is thousands of lines with the dead channels mixed in.
It works the same through `/raw LIST`.

A **wire traffic** buffer shows every line the server sent, for when a network does
something you would otherwise have to guess at.

## The server directory

Networks can be added, edited and removed in the UI, and are kept in the database.
`src/main/resources/config/servers.yml` is only the set a **fresh install starts
with** — it seeds an empty database and is then left alone, so an edit made in the UI
is never silently undone by a restart.

The ones it ships with, and what each is like:

| | |
| --- | --- |
| **local** / **local-tls** | the ergo server in the compose file; no account needed |
| **Libera.Chat** | the large general network; most free software projects live here |
| **OFTC** | Debian, Tor and similar; a different ircd |
| **IRCnet** | no services and no SASL, so nicks are first come first served |
| **EFnet** | no nick ownership either; expect to need a second choice of nick |
| **QuakeNet** | pings *during* registration, which some clients trip over |
| **Rizon** | anime and general chat; its own `CHANMODES`/`PREFIX` |
| **Twitch** | chat as IRC, with tags, capabilities and real rate limits |

**local** is there so a fresh install has somewhere to go without an account or an
internet connection. It is also the one to restart when you want to see what
reconnection looks like:

```bash
docker compose -f docker/compose.yaml restart ergo
```

The browser shows the connection drop, then reconnect with backoff, re-register and
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
cd src/test/js && npm ci && npm test           # the front end
```

Note `verify`, not `test`. The tests are named `*IT`, which surefire does not pick
up — `mvn test` ran nothing at all until failsafe was wired in, and reported success
while doing it. CI asserts both that the IRC server is reachable *and* that a non-zero
number of integration tests ran, because a suite that silently runs nothing is worse
than no suite.

The front end tests run `app.js` in jsdom and drive the input the way a person does,
because the command menu is logic and no Java test loads that file. They also assert
that every command offered is one the dispatcher implements — both read the same
table, so it is really asserting that they still do.

`LocalIrcServerIT` drives the whole stack against ergo: registration, joining and
member lists, a message travelling between two browser sessions through the IRC
server, and raw lines reaching the browser. It **skips** rather than fails when the
container is not running — worth remembering that a skipped test has proved nothing.

## Signing in

There is no default password, and nothing is printed to the log. Until an account
exists every page redirects to `/setup.html` and every API call is refused, so the
first thing a fresh install can do is create one — and the only person who can is
whoever reaches it first.

That is deliberate. A shipped default nobody changes and a generated password buried
in a log that later rotates away are the two usual ways something like this ends up
effectively unauthenticated; neither is possible if the password is chosen by a human
before anything works.

For an unattended install, set `IRC_WEB_ADMIN_USERNAME` and `IRC_WEB_ADMIN_PASSWORD`
**before the first start**. The account is created from them and setup never appears.
Set afterwards they do nothing — an account already exists, and letting an
environment variable overwrite a password would undo the point of asking for one.

### Resetting the password

Forgotten it? Delete the account row and restart with the password you want. The
networks, profiles and stored credentials are in other tables and are untouched:

```bash
docker compose -f docker/compose.yaml stop irc-web
# with the app stopped, so H2 is not locked:
java -cp h2.jar org.h2.tools.Shell -url jdbc:h2:/path/to/data/irc-web \
     -user sa -sql "DELETE FROM app_user"
IRC_WEB_ADMIN_PASSWORD=the-new-one docker compose -f docker/compose.yaml up -d irc-web
```

Leave `IRC_WEB_ADMIN_PASSWORD` unset instead and the next browser gets the setup page
again, which is the same thing without editing the database by hand.

## Staying connected

Signing in and connecting starts a session held by the server under your account.
From then on:

- Closing the tab, losing the network or shutting the laptop **detaches**. The IRC
  connection stays where it is, in its channels, under its nick.
- Opening the page again attaches to it. The window is rebuilt from the session —
  status, channels, who is in them — and the last **500 messages** are replayed.
- **Disconnect** is the only thing that leaves the network.

The replay is bracketed, so reattaching empties the window before refilling it. A
socket that drops and comes back would otherwise show every recent message twice.
Wire traffic is not kept: it is high volume and only interesting live, and keeping it
would push the actual conversation out of the backlog within seconds.

Backlog is in memory, so restarting the application loses it along with the
connections. Persisting it is a separate piece of work.

**One network at a time.** A session is keyed by account, which makes "am I already
connected?" a question with a single answer, and makes a second connection under the
same nick something you are told about rather than something that quietly happens.
Two browsers signed in as the same account share the one session — the second to
attach takes over, rather than both receiving half the traffic. Several networks at
once needs a window that can show them, which this does not have yet.

## What is stored, and how

The directory and a profile per server live in an H2 file under the data directory:
nick, channels, SASL account, and the two passwords. `servers.yml` **seeds an empty
database** and is then left alone — re-reading it on every start would silently undo
every edit made in the UI.

Passwords are encrypted at rest with AES-GCM. The key comes from
`IRC_WEB_SECRET_KEY`, or is generated into the data directory on first start so the
default path still encrypts rather than quietly storing plaintext. The generated file
is created owner-only in a single step, so it never exists in a readable state, and
is validated on every start — a truncated key fails loudly at startup rather than
surfacing later as passwords that mysteriously need re-entering.

Back it up with the database; without it the stored passwords cannot be read back,
which is an inconvenience rather than a loss since they can be entered again.

**The API never returns a stored password** — only whether one is set. This
application holds credentials for other people's networks, and a readable-back
password is one forgotten firewall rule from being someone else's. An untouched
password field leaves what is stored alone; an empty one clears it.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

Worth saying plainly: anyone who signs in can connect to any network in the directory
and send raw IRC commands from your address, using credentials this application holds
on your behalf. It is built for one person or a small trusted group, and it should sit
behind your own network or a reverse proxy with TLS rather than on the open internet.

## Depending on irc-client

```xml
<dependency>
    <groupId>io.github.aindriub</groupId>
    <artifactId>irc-client</artifactId>
    <version>1.0.0</version>
</dependency>
```
