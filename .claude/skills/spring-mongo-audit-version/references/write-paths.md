# Write-path matrices — what's maintained natively, and the two gaps

Verified live on Spring Data MongoDB 5.0–5.1 (Spring Boot 4.0–4.1). The headline: **`@Version` is
handled automatically on almost every path; auditing is handled on almost none.** That asymmetry is why
version needs only a guard (warn on two paths) while audit needs an active aspect.

## `@Version` — maintained natively?

| Write path | `@Version` maintained natively? |
|------------|---------------------------------|
| `save()` insert | ✅ sets `version = 0` |
| `save()` update | ✅ increments (+ optimistic-lock check) |
| `updateFirst` | ✅ increments |
| `updateMulti` | ✅ increments |
| `upsert` (update) | ✅ increments |
| `upsert` (insert) | ✅ new doc gets a version |
| `findAndModify` | ✅ increments |
| pipeline `AggregationUpdate` | ✅ increments |
| **`bulkOps`** | ❌ **not incremented** — field left untouched |
| **`findAndReplace`** | ❌ **removes the version field** unless the replacement carries it |

Only `save()` (and the upsert/explicit strategies) perform optimistic-lock *checking*; the template
update methods just keep the version value *maintained* so that `save()`-based locking stays correct.

## Audit fields — maintained natively?

| Write path | audit fields maintained? |
|------------|--------------------------|
| `save()` insert / update | ✅ (auditing fires on the convert path) |
| `updateFirst` / `updateMulti` / `upsert` / `findAndModify` | ❌ bypass auditing (need the aspect) |
| `upsert` (insert) | ❌ `By` fields null (aspect `$setOnInsert`) |
| `findAndReplace` | ❌ resets created fields (ban it) |
| `bulkOps` | ❌ maintains nothing (ban / set explicitly) |
| pipeline `AggregationUpdate` | ❌ (rare; handle explicitly) |

## The two gaps, and the fix

- **`bulkOps`** → add `.inc("version", 1)` to each bulk update, and set the audit fields explicitly;
  or avoid bulkOps on versioned/audited entities.
- **`findAndReplace`** → drops the version and resets created audit fields. Prefer `save()` /
  `findAndModify`; if you must use it, carry the current version and created fields on the replacement.

`VersionGuardAspect` (template) WARNs on these two for versioned entities; `AuditFieldsAspect` WARNs on
these two for audited entities; `AuditAndVersionArchTest` (template) can ban them at build time.

## Verifying on the running app (sanity probes)

```
POST /products {...}                          -> version 0, created/updated audit set
load → modify → save                          -> version 1, updated* refreshed, created* kept
updateFirst (set one field)                   -> version 2 (native), updated* set by aspect
findAndModify                                 -> version 3 (native)
bulkOps                                        -> WARN: version not incremented
findAndReplace                                 -> WARN: version dropped / created reset
```
