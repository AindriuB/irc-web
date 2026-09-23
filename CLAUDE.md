# irc-web

<!-- HARD CAP: 50 lines. This file is a router, not a manual.
     If you are adding detail here, it belongs in docs/ instead. -->

A self-hosted IRC client: Spring Boot 4 on Java 21, a browser front end of three
dependency-free files, and [irc-client](https://github.com/AindriuB/irc-client)
underneath. `mvn verify` builds and tests it at this level.

## Read on demand, not up front

| Need | File |
|---|---|
| How we work (the loop, roles, parallelism) | `docs/workflow.md` |
| Code style, naming, commit format | `docs/conventions.md` |
| What is open, in priority order | `docs/plan/PLAN.md` |
| What was already built — scan, never open `HISTORY.md` whole | `docs/plan/HISTORY-INDEX.md` |
| One task's full contract | `docs/plan/tasks/<id>.md` |
| System shape, threading rules, ports | `docs/architecture.md` |
| Running it, the server directory, what is stored | `README.md` |

Load exactly one of these when the task needs it. Do not preload the set.

## Before changing anything

- `mvn verify`, not `mvn test`. The tests are named `*IT` and surefire does not
  run them; failsafe does. `mvn test` reports success having run nothing.
- The integration tests need the local IRC server:
  `docker compose -f docker/compose.yaml up -d ergo`. They **skip** without it,
  and a skipped test has proved nothing.
- `cd src/test/js && npm ci && npm test` for the front end. `app.js` is a third
  of this application's behaviour and no Java test loads it.
- Jackson is **3**: `tools.jackson.databind`, not `com.fasterxml`, and its
  exceptions are unchecked. `TestRestTemplate` no longer exists — tests use
  `TestHttp`, a `RestTemplate` configured the three ways they relied on.

## Rules that hold everywhere

1. One task = one worktree = one branch. Never two agents in one tree.
2. A task file names the files it owns. Editing outside that set is a bug —
   stop and report instead.
3. Never paste file contents into a summary. Cite `path:line`.
4. Prefer `rg` over `grep`, and read ranges over whole files.
5. Secrets, tokens and dumps never leave the machine and never enter a doc.
6. Only `scribe` writes docs. Only `implementer` writes code.

## Roles

`explorer` recon · `architect` shape · `planner` tasks · `implementer` code ·
`tester` builds · `reviewer` diffs · `scribe` docs. Definitions in
`~/.claude/agents/`. The loop is `/plan` → `/fanout` → `/verify` → `/record`;
see `docs/workflow.md`.
