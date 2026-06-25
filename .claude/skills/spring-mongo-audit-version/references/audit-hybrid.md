# Audit fields — keep `createdAt/By` + `updatedAt/By` correct on every write

## The problem in one sentence

With `@EnableMongoAuditing`, the four fields populate automatically — **but only on the `save()` /
entity-conversion path.** Every `MongoTemplate` update bypasses auditing, so those paths leave the
fields stale, missing, or corrupted.

## Measured failure modes (live, per write path)

| Write path | createdAt | createdBy | updatedAt | updatedBy | verdict |
|------------|-----------|-----------|-----------|-----------|---------|
| `save()` insert | set | set | set | set | ✅ fully audited |
| `save()` update | kept | kept | refreshed | refreshed | ✅ fully audited |
| `updateFirst` / `updateMulti` / `upsert`(update) / `findAndModify` | kept | kept | only if caller `.set`s it | **STALE** | ❌ `updatedBy` forgotten |
| `upsert` (insert) | only if caller `.set`s it | **null** | only if caller `.set`s it | **null** | ❌ `By` fields missing |
| `findAndReplace` | **RESET to now** | **RESET to user** | reset | reset | ❌ created fields destroyed |

Three distinct failure modes, all on the template path:
1. **Forgotten field** — code that manually sets `updatedAt` on its updates still forgets `updatedBy`
   every time. (`updatedBy` is set by no template method, so its staleness everywhere is the proof that
   auditing never fires on these paths.)
2. **Missing on insert** — a template `upsert` that inserts leaves `createdBy`/`updatedBy` null.
3. **Reset/corruption** — `findAndReplace` *does* run auditing (it takes an entity), but treats the
   replacement as new, so it overwrites `createdAt`/`createdBy` with "now / current user" — destroying
   the original creation audit. **This is data loss**, the worst of the three.

## The fix is a hybrid (not either/or)

| Concern | Tool | From |
|---------|------|------|
| `save()` path | Spring Data `@EnableMongoAuditing` + `AuditorAware` | `AuditingConfig` template (add if absent) |
| Template writes *maintain* the fields | **AOP aspect** — inject `updatedAt/By` (`$set`) + `createdAt/By` (`$setOnInsert`), adding only what the caller didn't already set | `AuditFieldsAspect` template |
| Entities *declare* all four fields | **ArchUnit** rule over `@Document` classes | `AuditAndVersionArchTest` template |
| `findAndReplace` / `bulkOps` not used on audited types | **ArchUnit** ban (freeze to grandfather existing) | `AuditAndVersionArchTest` template |

**Why both AOP and ArchUnit:** ArchUnit proves the fields *exist* (structural) but cannot prove code
*fills* them; the aspect fills them (behavioural) but can't force entities to declare them or fix
`findAndReplace`/`bulkOps`. Neither alone is complete.

## How the aspect works (and its limits)

`AuditFieldsAspect` (template) advises `updateFirst`/`updateMulti`/`upsert`/`findAndModify` on an
audited entity (detected generically from the mapping context — it has `updatedAt` + `updatedBy`
properties) and injects:
- `updatedAt` / `updatedBy` via `$set` on every such write, and
- `createdAt` / `createdBy` via `$setOnInsert` (so they only land on the upsert-insert branch),

**only when the caller didn't already set that field** (`Update.modifies(...)`), so it never clobbers an
explicit value. It cannot safely fix:
- **`findAndReplace`** — whole-document swap that resets the created fields. The aspect WARNs; the right
  answer is to *not use it on audited types* (ArchUnit ban). Prefer `save()` / `findAndModify`.
- **`bulkOps`** — updates are registered on a separate builder the aspect can't see. WARN + ArchUnit ban,
  or have the caller add the audit fields explicitly to each bulk update.
- **pipeline (`AggregationUpdate`)** updates — WARN (rare; handle explicitly if you use them).

## The auditor source

`@CreatedBy`/`@LastModifiedBy` need a "current user". Wire the `AuditorAware` (save path) and the
aspect (template path) to the **same** source so they agree:
- **Production:** the security context (e.g. `SecurityContextHolder…getName()`).
- **Templates here:** a request-scoped `CurrentUser` populated from an `X-User` header
  (`CurrentUser` + `CurrentUserFilter` templates) — replace with real auth.
