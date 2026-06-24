# approach/naive

**Mechanism:** add `@Version Long version` to `Product` and nothing else.

**Hypothesis:** existing version-less documents load as `version == null`; Spring Data treats a
null version as "new" and routes `save()` to an insert, so saving a loaded legacy document fails
with a duplicate-key error on `_id`. New documents (created via the app) work fine.

**Run it:**
```
./mvnw spring-boot:run
# then, against :8080
POST /api/experiments/legacy-doc?name=Legacy%20Widget   -> id
POST /api/experiments/{id}/load-then-save               -> expected: DuplicateKeyException
POST /api/products {...}                                 -> new doc, gets version 0
POST /api/experiments/{newId}/concurrent-update          -> expected: 2nd writer locks
```

**Observed (live):** Q1 legacy `load-then-save` → ❌ `DuplicateKeyException` (E11000 dup key on `_id`). Q2 concurrent on the legacy doc → both writers hit the same duplicate-key error. Q3 brand-new doc → stored with `version=0`; concurrent update → 2nd writer gets `OptimisticLockingFailureException`. So legacy docs can never be saved, while new docs lock correctly — exactly the breakage the other branches must beat.
