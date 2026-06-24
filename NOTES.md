# approach/lazy-on-read

**Mechanism:** `@Version Long` + an `AfterConvertCallback<Product>` that sets `version = 0` whenever
a document loads without the field (`config/VersionDefaultingCallback`).

**Hypothesis:** defaulting the version on read makes the entity look "existing", so `save()` takes
the update path (not an insert) and the duplicate-key error from `approach/naive` goes away.

**Suspected caveat:** a versioned update filters on the loaded version — `{_id, version: 0}`. A
stored document with **no** `version` field is not matched by `version: 0` (only by `version: null`),
so the very first save of a legacy doc may still fail, now as an `OptimisticLockingFailureException`
(0 documents matched) rather than a duplicate key. This branch exists to confirm that.

**Run it:**
```
./mvnw spring-boot:run
POST /api/experiments/legacy-doc?name=Legacy%20Widget   -> id
POST /api/experiments/{id}/load-then-save               -> insert? update? lock failure?
POST /api/experiments/{id}/concurrent-update
```

**Observed (live):** Q1 legacy `load-then-save` → ❌ `OptimisticLockingFailureException` ("with version 1 … modified meanwhile"). The callback set `version=0`, so the versioned update filtered on `{_id, version:0}` — which does **not** match a document whose `version` field is absent — and 0 documents were modified. The duplicate-key error of `approach/naive` is merely traded for a lock error; the legacy doc still cannot be saved. New docs (Q3) lock fine. Conclusion: defaulting the version on read is fundamentally incompatible with the no-back-fill constraint, because only `version:null` matches an absent field and `null` routes straight back to an insert.
