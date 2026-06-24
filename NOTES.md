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

**Observed:** _(filled in from the live run — see APPROACHES.md matrix on `main`)_
