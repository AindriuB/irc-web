# History index

One row per `## YYYY-MM-DD` entry in `HISTORY.md`, newest first. Only `scribe`
edits this file, and it writes the row in the same commit as the entry.

`HISTORY.md` grows without bound and is the single largest file a session can
accidentally load. This index exists so `planner` can answer "has this been
built before, and where do I read about it?" without opening it. Rows are taken
from `HISTORY.md`'s own headings rather than re-summarized, so the index can be
checked against its source by eye.

Read a full entry by grepping the exact string in the **Heading** column:

```
grep -n '<heading text>' docs/plan/HISTORY.md
```

Then read that section only. A stale index is worse than none — an entry with
no row is one `planner` cannot find, and will re-plan.

| Date | Task IDs | Summary | Heading (grep this exact string) |
|---|---|---|---|
| 2026-09-24 | 01 | Fixed-blue branding: favicon/manifest icons, login/setup logo, CSS brand variables | ## 2026-09-24 — Fixed-blue branding: favicons, manifest, login/setup logo |
| 2026-09-24 | 01, 02, 03, 04 | Remember-me + 401 on /ws + client keep-alive fix silent 30-min session expiry; default port moved to 8667 | ## 2026-09-24 — Fixed silent session expiry, moved the default port to 8667 |
| 2026-09-24 | 02 | Brand assets and manifest serve unauthenticated before login and before first-run setup, with a BrandingIT covering path traversal | ## 2026-09-24 — Brand assets and manifest now serve without login or setup |
| 2026-09-24 | 01, 03 | CredentialRules rejects unusable server/SASL passwords and usernames on profile save; app.js shows save- and connect-failure reasons by the form without echoing credentials | ## 2026-09-24 — Bad credentials are now rejected on save and their errors shown by the form |
| 2026-09-24 | 02 | Connect failures now stop the bot they built (no orphaned reconnect loop) and report a fixed, cause-typed reason with no credential in it | ## 2026-09-24 — A failed connect now stops the bot instead of orphaning it |
