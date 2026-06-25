# Refactor breakage scenarios — what breaks, and how to fix it

When you switch on `@Version` (and audit fields) for an existing service, some tests and some prod
code that "worked" before start failing. Most are **test bugs** — the test encoded an assumption that
no longer holds. A few are **real bugs the refactor surfaces** (latent lost-update concurrency). This
is the catalog a refactor skill uses to recognise each one and apply the right fix.

Legend: 🧪 test bug (fix the test) · 🐛 real bug surfaced (fix the code — the new behaviour is correct)
· 🔧 expected change (update the expectation)

Runnable proof: branch **`scenarios/refactor-fixes`** (off `approach/custom-isnew`) —
`RefactorScenariosTest` encodes V1–V6 as Testcontainers tests, each asserting that the **broken**
pattern throws and the **fixed** pattern works.

---

## Adding `@Version`

| # | Symptom | Kind | Fix |
|---|---------|------|-----|
| V1 | A test fabricates an "existing" entity (preset id, ± version) then `save()` → `OptimisticLockingFailureException` | 🧪 | don't fabricate a managed entity; insert a real one, then load → modify → save |
| V2 | `save()` on a legacy version-less doc → `DuplicateKeyException` | 🐛/data | the migration strategy (`custom-isnew`/`upsert-all-cases`) fixes it transparently |
| V3 | Concurrent load-modify-save now throws `OptimisticLockingFailureException` | 🐛 | correct — it caught a real lost update; add an optimistic-retry loop, don't suppress |
| V4 | A long-held / detached entity saved later → `OptimisticLockingFailureException` | 🧪/🐛 | reload before saving; shorten the load→save window |
| V5 | Test asserts `version` is absent or a fixed value | 🔧 | assert "present & increments", not absent/exact |
| V6 | New entity built with id **and** a non-null version (doc doesn't exist) → `OptimisticLockingFailureException` | 🧪 | new entities carry a null version; for migration/replication use `mongoTemplate.insert`, not `save()` |
| V7 | Code uses `bulkOps` / `findAndReplace` and relied on the version | 🐛 | `bulkOps`: add `.inc("version",1)`; `findAndReplace`: avoid / carry the version |

### V1 — fabricated "existing" entity *(test bug)*
```java
// BROKEN: pretends a never-persisted object is an existing one
Product p = new Product("64f...preset", "Keyboard", "electronics", price, 5);
p.setVersion(0L);
repository.save(p);                 // -> OptimisticLockingFailureException (no such doc)

// FIX: persist a real one, then load-modify-save
Product created = repository.save(new Product(null, "Keyboard", "electronics", price, 5));
Product loaded  = repository.findById(created.getId()).orElseThrow();
loaded.setStock(6);
repository.save(loaded);            // version 0 -> 1
```

### V2 — legacy version-less doc *(data; fixed by the strategy)*
On `approach/naive` this `save()` throws `DuplicateKeyException`. On `custom-isnew` / `upsert-all-cases`
the same call migrates the doc to `version 0` — no code change at the call site. (This is the whole
point of choosing a migration strategy; see `APPROACHES.md`.)

### V3 — concurrent modification now caught *(real bug surfaced)*
```java
// The exception is CORRECT: two writers raced and one would have lost an update silently before.
Product retried = saveWithRetry(id, p -> p.setStock(p.getStock() - 1));

<T> T saveWithRetry(String id, Consumer<Product> mutate) {
    for (int i = 0; i < MAX_RETRIES; i++) {
        Product p = repository.findById(id).orElseThrow();
        mutate.accept(p);
        try { return repository.save(p); }
        catch (OptimisticLockingFailureException retry) { /* reload & try again */ }
    }
    throw new IllegalStateException("optimistic retries exhausted");
}
```

### V4 — stale / detached entity *(test bug or prod anti-pattern)*
Holding an entity across a long gap and saving it later fails if anything else touched the doc. Fix:
reload immediately before the write and re-apply the change (or restructure to avoid the long window).

### V5 — assertions on the version field *(expected change)*
`assertThat(p.getVersion()).isNull()` → `assertThat(p.getVersion()).isZero()` after create, and
increments on each update.

### V6 — new entity with a preset version *(test bug)*
A non-null version means "an existing entity at version N". Building a *new* object with one (id set)
throws by the `@Version` contract — on every strategy. New objects must have a null version.

### V7 — `bulkOps` / `findAndReplace` *(silent prod bug)*
These don't maintain the version (`findAndReplace` drops it). Add `.inc("version",1)` to bulk updates;
replace `findAndReplace` with `save()`/`findAndModify` or carry the version explicitly. See
`VERSION-WRITES.md`.

---

## Adding audit fields

| # | Symptom | Kind | Fix |
|---|---------|------|-----|
| A1 | Test asserts `createdAt`/`updatedAt` null or a hardcoded value | 🧪 | assert non-null / inject a fixed `Clock`; don't assert exact instants |
| A2 | Test sets `createdBy`/`updatedBy` manually and asserts them | 🧪 | set the auditor (`AuditorAware` / security context) under test and assert that |
| A3 | Test expected `updatedAt` unchanged after a template update | 🔧 | the AOP aspect now maintains it (`aop/auto-audit`) — update the expectation |

### A1/A2 — auditing now owns the fields *(test bug)*
```java
// BROKEN: auditing overwrites these on save()
product.setCreatedAt(FIXED); product.setCreatedBy("me");
assertThat(saved.getCreatedBy()).isEqualTo("me");          // fails

// FIX: drive the auditor, assert the audited result
withCurrentUser("alice", () -> repository.save(product));
assertThat(saved.getCreatedBy()).isEqualTo("alice");
assertThat(saved.getCreatedAt()).isNotNull();
```

---

## How a refactor skill should use this
1. After enabling `@Version`/auditing, run the test suite.
2. Match each failure to a row above by exception type + pattern.
3. Apply the fix for its **kind**: 🧪 rewrite the test, 🐛 fix the code (add retry / fix concurrency /
   maintain the field) — never just silence the exception, 🔧 update the assertion.
4. `OptimisticLockingFailureException` from concurrency (V3) and `DuplicateKeyException` on legacy data
   (V2) are the two that most often hide a real issue — treat them as 🐛 until proven otherwise.
