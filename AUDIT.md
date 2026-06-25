# Audit fields: maintaining `createdAt/By` + `updatedAt/By` on every write

A second investigation, same shape as the version one. Persisted objects need four audit fields
— `createdAt`, `createdBy`, `updatedAt`, `updatedBy` — kept correct on **every** write, going forward
(no back-fill). Some entities already have them, some don't have them all.

## Baseline (`main`): standard Spring Data auditing

`Product` carries the four fields wired to `@CreatedDate / @CreatedBy / @LastModifiedDate /
@LastModifiedBy`, with `@EnableMongoAuditing` and an `AuditorAware<String>` fed by a per-request
`X-User` header (`config.AuditingConfig`, `CurrentUser`, `CurrentUserFilter`). This is the
conventional "production today" setup.

The key fact — and the whole problem — is that **Spring Data auditing only fires on the
`save()` / entity-conversion path.** The `MongoTemplate` update operations bypass it. (Contrast with
`@Version`, which Spring Data 5.x *does* auto-increment on `updateFirst/updateMulti/upsert/findAndModify`.)

## Measured behaviour (live, per write path)

Read the audit fields after each write via `GET /api/status/audit/{id}`:

| Write path | createdAt | createdBy | updatedAt | updatedBy | verdict |
|------------|-----------|-----------|-----------|-----------|---------|
| `save()` insert | set | set | set | set | ✅ fully audited |
| `save()` update | kept | kept | refreshed | refreshed | ✅ fully audited |
| `updateFirst` | kept | kept | manual `.set` | **STALE** | ❌ updatedBy forgotten |
| `findAndModify` | kept | kept | manual `.set` | **STALE** | ❌ updatedBy forgotten |
| `updateMulti` | kept | kept | manual `.set` | **STALE** | ❌ updatedBy forgotten |
| `upsert` (update) | kept | kept | manual `.set` | **STALE** | ❌ updatedBy forgotten |
| `upsert` (insert) | manual `.set` | **null** | manual `.set` | **null** | ❌ By fields missing |
| `findAndReplace` | **RESET to now** | **RESET to user** | reset | reset | ❌ created fields lost |

Three distinct failure modes, all on the template path:

1. **Forgotten field** — the codebase manually sets `updatedAt` on its template updates but forgets
   `updatedBy` *every time*, so `updatedBy` goes stale. (`updatedBy` is set by no template method, so
   its staleness everywhere is the clean proof that auditing never fires on these paths.)
2. **Missing on insert** — a template `upsert` that inserts leaves `createdBy`/`updatedBy` null.
3. **Reset/corruption** — `findAndReplace` *does* run auditing (it takes an entity), but because the
   replacement is treated as new it **overwrites `createdAt`/`createdBy`** with "now / current user",
   destroying the original creation audit.

## The two paths (to build next)

| Path | Idea | Trade-off |
|------|------|-----------|
| **archunit/** | ArchUnit rules that fail the build when an `@Document` is missing audit fields, or when a `MongoTemplate` update on an audited entity doesn't set `updatedAt`+`updatedBy`. | Enforces discipline; bigger code impact (must fix every existing call site). |
| **aop/** | An aspect that injects the audit fields into every template write — `updatedAt`/`updatedBy` always, `createdAt`/`createdBy` on insert — adding only what's missing. | No call-site changes; must cover all four fields and all write methods, and not clobber existing created values. |

Both are "going forward" only — no back-fill. Findings here are measured on `main`; the branches
will be built and verified the same way the version strategies were.
