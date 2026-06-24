# Approaches: adding `@Version` without a back-fill

The constraint: a live `products` collection already holds documents with **no `version` field**, and
we may **not** run a bulk back-fill. Each branch below adds `@Version` with a different mechanism and
is judged on three questions:

1. **Legacy save** — does `load-then-save` on a version-less doc succeed, and what gets written?
2. **Locking** — do concurrent updates still raise `OptimisticLockingFailureException`?
3. **New docs** — do freshly created docs behave normally?

> Status legend: ✅ works · ⚠️ works with caveat · ❌ breaks · ⏳ not yet measured

## Matrix

| Branch | Mechanism | Legacy save | Locking preserved | Notes |
|--------|-----------|-------------|-------------------|-------|
| `approach/naive` | `@Version Long` only | ❌ duplicate-key | n/a for legacy | control: this is the breakage to beat |
| `approach/lazy-on-read` | `AfterConvertCallback` sets `version=0` when null | ⏳ | ⏳ | does the optimistic filter `{version:0}` match a doc with no version field? |
| `approach/explicit-upsert` | custom `save` → `replaceOne(_id, upsert)` | ⏳ | ⏳ | bypasses `@Version` routing; locking must be hand-rolled |
| `approach/custom-isnew` | `Product implements Persistable`, `isNew = id==null` | ⏳ | ⏳ | null-version update filter is `{version:null}`, which matches a missing field |

Findings are filled in from live runs as each branch is built. Each branch also carries its own
`NOTES.md` with the exact request/response transcript.

## How each branch is exercised

Identical script on every branch (see `requests.http`):

```
POST /api/experiments/legacy-doc?name=...   -> id of a version-less doc
GET  /api/products/{id}/raw                 -> confirm no version field
POST /api/experiments/{id}/load-then-save   -> Q1: legacy save behaviour
POST /api/experiments/{id}/concurrent-update-> Q2: locking behaviour
POST /api/products  + concurrent-update      -> Q3: new-doc behaviour
GET  /api/status/version-stats              -> how many docs still lack version
```

## Why "no back-fill" is the whole problem

With a back-fill you would `updateMany({version:{$exists:false}}, {$set:{version:0}})` once, and every
document would then load with a non-null version and update normally. That approach lives on
`approach/version-with-backfill` for reference. Everything else here is about achieving the same end
state **lazily / per-document / at the application layer**, because the one-shot bulk write is off
the table.
