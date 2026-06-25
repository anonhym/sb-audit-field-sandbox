# Which write paths maintain `@Version`?

Once `@Version` exists on an entity, does every write keep it correct? Measured live (Spring Data
MongoDB 5.1 / Spring Boot 4.1, and re-verified identical on 4.0.7 / Spring Data 5.0.6).

**Finding:** Spring Data **already auto-increments `@Version` on its template update methods.** A
once-common belief that you must hand-maintain the version on `updateFirst`/`updateMulti` was true of
*older* Spring Data; it isn't on 5.x. So a blanket "increment everywhere" interceptor is unnecessary.

## Native behaviour (no aspect)

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

The only real gaps are **`bulkOps`** and **`findAndReplace`**. Note the template update methods only
*maintain* the version value — they do **not** add optimistic-lock *checking* (they update whatever
matches the query); the check still happens on the `save()` path, which the maintained version keeps
correct.

> ⚠️ Boundary (see [[version-id-version-boundary]] / `approach/upsert-all-cases` NOTES): saving a
> brand-new entity built with an id **and** a non-null version throws `OptimisticLockingFailureException`
> by the `@Version` contract — identical on every strategy. New entities must have a null version.

## The guard (`feature/version-guard`)

`config/VersionGuardAspect` logs a WARN when `bulkOps` or `findAndReplace` is used on a versioned
entity (it does not try to fix them — Spring Data covers the rest, and these two can't be safely
rewritten from an aspect). Needs `aspectjweaver` (`spring-boot-starter-aop` was removed in Boot 4).

- `bulkOps` → add `.inc("version", 1)` to each bulk update.
- `findAndReplace` → prefer `save()`/`findAndModify`, or carry the current version on the replacement.

## Verify

```
POST /api/products {...}                       -> version 0
POST /api/experiments/{id}/inc-stock           -> version 1   (updateFirst, native)
POST /api/updates/{id}/find-and-modify         -> version 2   (findAndModify, native)
POST /api/updates/bulk?category=electronics    -> bulkOps: version NOT incremented (guard WARNs)
PUT  /api/updates/{id}/find-and-replace {...}  -> findAndReplace: version dropped (guard WARNs)
```
