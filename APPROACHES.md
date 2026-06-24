# Approaches: adding `@Version` without a back-fill

The constraint: a live `products` collection already holds documents with **no `version` field**, and
we may **not** run a bulk back-fill. Each branch below adds `@Version` with a different mechanism and
is judged on:

1. **Legacy save** — does `load-then-save` on a version-less doc succeed, and what gets written?
2. **Locking** — do concurrent updates raise `OptimisticLockingFailureException`?
3. **New docs** — do freshly created docs behave normally?

> Status legend: ✅ works · ⚠️ works with caveat · ❌ breaks

## Results (measured live against mongo:8, Spring Boot 4.1)

| Branch | Mechanism | Legacy save (Q1) | Locking | New docs | Verdict |
|--------|-----------|------------------|---------|----------|---------|
| `approach/naive` | `@Version` only | ❌ `DuplicateKeyException` (E11000) | new docs only | ✅ | control — broken |
| `approach/lazy-on-read` | `AfterConvertCallback` sets `version=0` on read | ❌ `OptimisticLockingFailureException` | new docs only | ✅ | **does not solve it** |
| `approach/custom-isnew` | `Persistable`, `isNew = id==null` | ✅ SAVED → `version=0` | ✅ full | ✅ | **cleanest** ✅ |
| `approach/explicit-upsert` | custom repo `replaceOne(_id)` | ✅ SAVED → `version=0` | ✅ (from 2nd write) | ✅ | works; more code; ⚠️ first-race |
| `approach/version-with-backfill` | bulk `$set version=0`, then `@Version` | ✅ | ✅ | ✅ | reference — **forbidden** here |

## The key finding: it's all about the optimistic-update filter

When Spring Data updates a versioned entity it issues `updateFirst({_id, version: <loaded>}, …)`.
The deciding fact is a MongoDB equality-match detail:

- **`{version: null}` matches a document whose `version` field is absent** (null == missing).
- **`{version: 0}` does NOT** — it only matches a stored `0`.

That single rule explains the whole table:

- **`custom-isnew` ✅** — it changes *only* the new/existing decision and leaves the loaded version
  `null`. The update filter is `{_id, version: null}`, which matches the legacy doc, so the first
  save stamps `version = 0` and every subsequent save locks normally. Migrate-on-touch, no back-fill.
- **`lazy-on-read` ❌** — defaulting the loaded version to `0` makes the filter `{_id, version: 0}`,
  which *misses* the version-less doc → 0 documents modified → `OptimisticLockingFailureException`.
  It just trades the duplicate-key error for a lock error; the legacy doc can never be saved this way.
  (There is no non-null default that works — only `null` matches an absent field, and `null` routes
  back to an insert. So an `AfterConvertCallback` default is fundamentally incompatible with the
  no-back-fill constraint.)
- **`explicit-upsert` ✅⚠️** — sidesteps Spring Data's routing entirely with `replaceOne` keyed on
  `_id`, so a null version is never mis-routed to an insert. Locking is hand-rolled (filter on
  `version` when non-null). **Caveat:** the first migrating write of a legacy doc has no prior version
  to check, so two writers racing on a *not-yet-migrated* doc can both win once; locking holds from
  the next write on. More moving parts (a custom repository base class) than `custom-isnew`.

## Recommendation

`approach/custom-isnew` is the smallest change that fully works: one interface
(`Persistable`) on the entity, no extra beans, no custom repository, optimistic locking intact from
the first write, and legacy documents migrate to `version = 0` the moment they're next saved. The
one thing to know: `isNew = (id == null)` assumes ids are server-assigned — saving a brand-new entity
with a *client-supplied* id would be treated as an update.

## How each branch was exercised

Identical probe on every branch (`tmp/probe.sh`, mirrors `requests.http`):

```
POST /api/experiments/reset
POST /api/experiments/legacy-doc?name=...      -> version-less doc id
GET  /api/products/{id}/raw                     -> confirm no version field
POST /api/experiments/{id}/load-then-save       -> Q1: legacy save
POST /api/experiments/{id}/concurrent-update    -> Q2: locking
POST /api/products {...}                         -> new doc
POST /api/experiments/{newId}/concurrent-update -> Q3: new-doc locking
GET  /api/status/version-stats                  -> how many docs still lack version
```

Each branch's `NOTES.md` holds its hypothesis; the measured numbers above come from a full live run.
