# approach/upsert-all-cases

Branched from `approach/explicit-upsert`. Goal: cover **every** save shape, including the one no
other branch handles — a **brand-new document whose id is set before saving** (client-assigned id).

## The ambiguity

A document with `id` set and `version == null` is indistinguishable, from the entity alone, between:

- a **pre-existing legacy** document (must UPDATE / migrate), and
- a **brand-new** document with a client-assigned id (must INSERT).

| Branch | new doc, client-set id | legacy doc | both? |
|--------|------------------------|------------|-------|
| naive | ✅ insert | ❌ duplicate-key | no |
| lazy-on-read | ✅ insert | ❌ optimistic-lock | no |
| custom-isnew | ❌ optimistic-lock | ✅ migrate | no |
| explicit-upsert | ❌ optimistic-lock | ✅ migrate | no |
| **upsert-all-cases** | ✅ insert | ✅ migrate | **yes** |

## Mechanism

Custom repository base class (`config/UpsertMongoRepository`) overriding `save()`:

- **id == null** → normal insert (`@Version` → 0).
- **id set, version == null** (first write) → `replaceOne({_id}, doc, upsert = true)`. One idempotent
  operation: replaces a legacy doc if it exists, inserts a client-id doc if it doesn't. Stamps
  `version = 0`.
- **id set, version present** → `replaceOne({_id, version}, doc, upsert = false)`; `matchedCount == 0`
  → `OptimisticLockingFailureException`.

The only change from `explicit-upsert` is `upsert(firstWrite)` instead of `upsert(false)` (and not
throwing on the first-write path).

**Trade-off (unchanged):** the first write of a not-yet-versioned document has no prior version to
check, so two writers racing on it can both win that once; optimistic locking applies from the next
write onward.

## Verify

```
./mvnw spring-boot:run
POST /api/experiments/save-new-with-id?name=X     -> SAVED, version 0   (new doc, client-set id)
POST /api/experiments/legacy-doc?name=Y           -> id
POST /api/experiments/{id}/load-then-save         -> SAVED, version 0   (legacy migrated)
POST /api/products {...}                           -> SAVED, version 0   (normal new, id==null)
POST /api/experiments/{id}/concurrent-update      -> 2nd writer locks
```

## Boundary: a new object built with id AND a non-null version

Saving a brand-new document (an id that does not exist) whose `version` is also set to a non-null
value throws `OptimisticLockingFailureException` — it is **not** inserted. Path `[C]` in `save()`
filters on `{_id, version}` with `upsert = false`, finds nothing, and raises the lock failure.

This is **not specific to this branch.** Measured live, plain `@Version` (naive), lazy-on-read,
custom-isnew, and explicit-upsert all behave identically. It is Spring Data's optimistic-locking
contract: a non-null version means "an existing entity at version N", so if no such document exists
that is a conflict — exactly as JPA `@Version` behaves. Even `version = 0` counts as non-null here.

A brand-new entity must therefore carry a `null` version (or a `null` id). If you genuinely need to
insert documents that already carry a version — data migration / replication / event-sourcing
rehydration — use a direct insert (`mongoTemplate.insert(...)` / a bulk insert), not
`repository.save()`. Changing `save()` to insert in this case would diverge from the framework
contract and could mask real stale-update conflicts, so it is deliberately left as-is.

Remaining edge: two threads concurrently saving the *same* new client-assigned id (both first-write,
`upsert=true` on `{_id}`) can race — MongoDB may raise `DuplicateKeyException` for the loser of the
insert (the documented upsert/_id race) rather than a clean optimistic-lock failure. Retry on
duplicate key if that pattern is possible in your system.
