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
