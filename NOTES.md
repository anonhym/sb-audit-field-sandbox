# approach/custom-isnew

**Mechanism:** `@Version Long` + `Product implements Persistable<String>` with
`isNew() == (id == null)`. The new-vs-existing decision is taken from the id, not the version.

**Hypothesis:** a loaded legacy document has an `_id` but a null version. With `isNew()` keyed on the
id, it is treated as existing, so `save()` takes the versioned update path. The loaded version is
`null`, so the update filter is `{_id, version: null}` — and in MongoDB `version: null` matches a
document where the field is **absent** — so the update finds the legacy doc and writes a version
onto it. No back-fill, no duplicate key. New docs (id null) still insert.

**Watch for:** what version value the first update writes (0 or 1), and whether concurrent updates on
an already-migrated doc still raise `OptimisticLockingFailureException`. Edge case: saving a
hand-constructed entity with a non-null id that does *not* exist would be treated as an update.

**Run it:**
```
./mvnw spring-boot:run
POST /api/experiments/legacy-doc?name=Legacy%20Widget   -> id
POST /api/experiments/{id}/load-then-save               -> expected: SAVED, version stamped
POST /api/experiments/{id}/concurrent-update             -> expected: 2nd writer locks
```

**Observed (live):** Q1 legacy `load-then-save` → ✅ SAVED, `version` stamped to `0`. With the loaded version left `null`, the update filtered on `{_id, version:null}`, which matches the version-less document, so the legacy doc migrated in place with no back-fill. Q2 concurrent (on the now-migrated doc) → 2nd writer `OptimisticLockingFailureException`. Q3 new doc → locks normally. `version-stats` afterwards: 0 documents missing the field. Cleanest result — migrate-on-touch with optimistic locking intact from the first write. Caveat: `isNew = (id == null)` assumes server-assigned ids.
