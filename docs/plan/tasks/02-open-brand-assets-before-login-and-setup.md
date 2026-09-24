# 02 — Serve brand assets and the manifest without login or setup, with an IT

**Repo:** /srv/dev/projects/irc-web
**Depends on:** 01
**Owns:**
- src/main/java/io/github/aindriub/ircweb/security/SecurityConfig.java
- src/main/java/io/github/aindriub/ircweb/security/SetupRequiredFilter.java
- src/test/java/io/github/aindriub/ircweb/BrandingIT.java

## Goal
Browsers fetch favicons and manifests without cookies, and the login and setup
pages show them before anyone is signed in. Make `/brand/**` and
`/manifest.webmanifest` reachable unauthenticated and before first-run setup,
changing nothing else about access, and prove it with an integration test.

## Context
- SecurityConfig.java:70-73 — the `permitAll` requestMatchers list
- SetupRequiredFilter.java:25-26 — the `OPEN` exact-match set; `doFilterInternal` checks `OPEN.contains(path)` — `/brand/**` needs a prefix check (`path.startsWith("/brand/")`) alongside it
- src/test/java/io/github/aindriub/ircweb/FirstRunIT.java — ordered before-setup / after-setup IT pattern to follow (`@TestMethodOrder`, `TestHttp`, `/api/setup` POST to create the account)
- docs/plan/HISTORY-INDEX.md — the ITs share one ergo container; never run irc-web ITs in parallel
- CLAUDE.md — `mvn verify`, not `mvn test`; ITs skip without ergo (`docker compose -f docker/compose.yaml up -d ergo`)

## Acceptance
- [ ] SecurityConfig permits exactly `/brand/**` and `/manifest.webmanifest` in addition to the existing list; no existing matcher is removed or broadened
- [ ] SetupRequiredFilter lets `/manifest.webmanifest` and paths starting `/brand/` through while setup is needed; the javadoc on `OPEN` mentions them; every other path's behaviour is unchanged
- [ ] New `BrandingIT` (own fresh database like FirstRunIT) asserts, before setup: unauthenticated GET `/brand/favicon.svg` → 200 `image/svg+xml`, `/brand/icon-192.png` → 200 `image/png`, `/manifest.webmanifest` → 200 `application/manifest+json`; and `/index.html` does not return the app (redirects to setup)
- [ ] After creating the account via `/api/setup`, the same brand/manifest GETs without credentials still return 200 with those content types, and unauthenticated GET `/index.html` and `/app.js` do not return 200 with their content (redirect to /login.html or 401)
- [ ] `mvn verify` with ergo running passes, with BrandingIT, FirstRunIT and DirectoryAndSecurityIT all executed (not skipped), ITs run serially

## Out of scope
- Any static file, HTML, CSS or application.yml — task 01 (if the manifest content type is wrong, report back rather than editing)
- Loosening any other path (e.g. style.css, app.js, `/favicon.ico` handling)
- JS tests
