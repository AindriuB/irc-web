# Architecture

The shape of the system: what the pieces are, what talks to what, and where the
boundaries are. `explorer` and `architect` read this before inferring structure
from the filesystem, so keeping it honest saves every session a search.

Keep it to the parts that are stable.

## Components

| Component | Directory | Owns |
|---|---|---|
| `irc/` | `src/main/java/.../irc` | Connections, the registry that holds them, the browser event contract |
| `web/` | `src/main/java/.../web` | The websocket handler and the REST controllers |
| `security/` | `src/main/java/.../security` | Sign-in, first-run setup, encryption of stored passwords |
| `store/` | `src/main/java/.../store` | JPA entities and repositories |
| `static/` | `src/main/resources/static` | The whole front end: `index.html`, `app.js`, `style.css` |

One process, port **8667** by default; the dev compose stack and local runs
use **8081** instead, via `SERVER_PORT`. Its own ergo IRC server on 6667/6697,
bound to loopback, started by `docker/compose.yaml`. Data — H2 file,
encryption key — lives under `IRC_WEB_DATA_DIR`.

## How they talk

```
browser ──WebSocket(JSON)──▶ IrcWebSocketHandler ──▶ LiveSession ──▶ IRCBot ──▶ server
        ◀───────────────────                      ◀── BotListener ◀──
        ──fetch(JSON)──▶ ServerDirectoryController / SetupController ──▶ DirectoryService
```

The browser contract is two records and nothing else: `ClientCommand` in,
`OutboundEvent` out, both switched on a `type` string. A field added to either
is a change to a contract `app.js` reads directly — there is no generated client
to catch a mismatch, so the browser must be changed in the same commit.

## Boundaries that must not be crossed

1. **A `WebSocketSession` is not safe for concurrent use.** IRC events arrive on
   whichever Netty thread was reading. Every send is serialised on a per-session
   lock. Sending from a new path without that lock corrupts the frame stream.
2. **Nothing that talks to IRC runs on a container thread.** `IRCBot.start()`
   blocks until registration completes and a send can block on flood protection.
   Both go to the worker pool in `IrcWebSocketHandler`.
3. **A session belongs to an account, not to a socket.** `IrcSessionRegistry`
   owns it; a socket attaches and detaches. Closing a socket must never close
   an IRC connection — that is the bouncer, and it is one line away from being
   undone.
4. **The API never returns a stored password**, only whether one is set.
   Anything that would serialise `ServerProfile` credentials outward is a defect
   regardless of how it reads.
5. **`servers.yml` seeds an empty database and is then left alone.** Re-reading
   it on every start would silently undo every edit made in the UI.

## Decisions worth knowing

- **Spring Boot 4 / Jackson 3** (2026-09-22). Jackson's package moved to
  `tools.jackson` and its exceptions became unchecked; `TestRestTemplate` was
  removed. The tests kept their shape via `TestHttp` rather than being rewritten
  around `RestTestClient`. Rejected: staying on Boot 3.
- **Plain WebSocket, not STOMP.** One socket carrying a handful of message types;
  STOMP would add a broker, a client library and a subscription model to say the
  same thing. Rejected: STOMP over SockJS.
- **No front end build step.** Three files in `static/`, no CDN, no bundler. The
  cost is no framework; the gain is that the app is the deployable artifact.
- **H2 in file mode, not a database server.** A single-user application should
  not need one stood up beside it. Rejected: Postgres in compose.
- **First-run setup wizard, no default password** (2026-09-22). Jellyfin's
  shape. Rejected: `admin/admin` with a forced change, and a generated password
  printed once to the log — the latter was the previous behaviour and locked the
  owner out when the container was recreated.
- **Backlog is in memory, capped at 500 messages per session** (2026-09-22).
  Restarting the app loses it. Persisting it was explicitly deferred.
- **One network at a time**, keyed by account. Makes "am I already connected?"
  a question with one answer. Several at once needs a window that can show them.

## Session model

The SPA talks only over its WebSocket after load, and socket traffic never
refreshes the HTTP session on its own — that gap is what used to close the
socket with 1008 thirty minutes after page load and then loop on 1006
forever, described in HISTORY. Three pieces keep it working now:

- **Remember-me.** Spring Security persistent-token remember-me
  (`JdbcTokenRepositoryImpl` on H2, 30 days). The token key is an HMAC of a
  label under `SecretCodec`'s key, not a raw secret. The login page's
  "Remember me" checkbox is checked by default, so a browser normally carries
  the cookie without the user doing anything. Logout deletes the row even
  when the request is authenticated only by the cookie — `LogoutFilter` runs
  before `RememberMeAuthenticationFilter` — but only after a constant-time
  check of the cookie's token against the stored row.
- **Client keep-alive.** `app.js` pings `/api/me` every 5 minutes while the
  socket is open, which keeps the HTTP session itself alive so the common
  case never needs the remember-me cookie at all.
- **`/ws/**` answers 401, not 302.** A browser's WebSocket API cannot see a
  redirect — it reports one only as an opaque 1006 close — so an
  unauthenticated upgrade must fail with a status the client can act on.
  Before reopening after a close, `app.js` probes `/api/me` over plain HTTP:
  a 401 sends it to login, a network error backs off and retries.

**Deployments behind a TLS proxy** must set
`server.forward-headers-strategy=framework` (or `native`) in the deployment
environment, not in `application.yml`. It is deliberately not in the
checked-in config because the dev compose stack binds all interfaces without
a proxy in front of it; trusting `X-Forwarded-*` there would let anyone who
can reach the container spoof scheme and host.

## Repository topology

This repository is self-contained: `git clone` and `mvn verify` is the whole
setup. It depends on `irc-client` as an ordinary Maven artifact from Central,
not as a sibling checkout — the version is the `irc-client.version` property in
`pom.xml`. `../irc-client` may exist locally, but nothing here reads it.

`.claude/` and `.worktrees/` are gitignored on purpose. `.claude/` holds the
worktree scripts installed from the agent kit, whose single clone lives at
`~/.claude-kit` and is refreshed with `git -C ~/.claude-kit pull`. The roles are
not here at all — `agents/`, `commands/` and `skills/` are symlinked into
`~/.claude` and shared by every project. Tracking any of it here would fork the
harness away from that upstream.
