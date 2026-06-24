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

**Observed:** _(filled in from the live run — see APPROACHES.md matrix on `main`)_
