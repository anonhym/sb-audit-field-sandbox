# scenarios/refactor-fixes

The runnable companion to [`REFACTOR-SCENARIOS.md`](REFACTOR-SCENARIOS.md) (on `main`). Branched off
`approach/custom-isnew`, so `Product` has `@Version` + `Persistable` — the post-refactor state.

`src/test/java/.../scenarios/RefactorScenariosTest` encodes the breakage catalog as **Testcontainers**
integration tests (real `mongo:8`). Each test asserts the **broken** pattern fails and the **fixed**
pattern works:

| Test | Scenario | Broken → Fixed |
|------|----------|----------------|
| V1 | fabricated "existing" entity (preset id+version) | `OptimisticLockingFailureException` → insert then load-modify-save |
| V2 | legacy version-less doc | migrates to `version 0` (custom-isnew), not duplicate-key |
| V3 | concurrent modification | `OptimisticLockingFailureException` → optimistic-retry loop |
| V4 | stale/detached entity | `OptimisticLockingFailureException` → reload before save |
| V5 | version field assertions | present & increments (was asserted null) |
| V6 | new entity with id + non-null version | `OptimisticLockingFailureException` → null version on new objects |

Run: `./mvnw test -Dtest=RefactorScenariosTest` (needs Docker). Testcontainers is **2.0.5** (Boot 4.1
manages it) — note the 2.x module names `testcontainers-junit-jupiter` / `testcontainers-mongodb`.
