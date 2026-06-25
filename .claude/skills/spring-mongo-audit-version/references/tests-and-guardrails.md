# Tests & guardrails

Two layers, both required, because each covers what the other can't:
- **ArchUnit (structural)** — proves every `@Document` *declares* the five fields with the right
  annotations, and that the unsafe write paths aren't used. Build-time, no Mongo needed.
- **Behavioural (Testcontainers)** — proves the fields are actually *maintained* at runtime: version
  increments, audit fields stay correct across `save()` and a `MongoTemplate` update, and a concurrent
  update throws `OptimisticLockingFailureException`.

ArchUnit can't prove runtime behaviour; the behavioural test can't prove *every* entity declares the
fields. Ship both.

## ArchUnit — `AuditAndVersionArchTest` (template)

Add `com.tngtech.archunit:archunit-junit5` (test scope; verified with 1.4.x). Adjust the import root
package. The template has three rules:
1. **every `@Document` declares the four audit fields** with `@CreatedDate`/`@CreatedBy`/
   `@LastModifiedDate`/`@LastModifiedBy`.
2. **every `@Document` declares `@Version version`** — *scope this* if versioning is per-collection:
   restrict to a marker interface/annotation or sub-package rather than all `@Document` classes.
3. **no NEW code calls `findAndReplace` / `bulkOps`** — wrapped in `FreezingArchRule` to grandfather
   existing legitimate call sites and only block new ones. Two things that *will* bite (both learned the
   hard way, both already handled in the template):
   - **Match owners _assignable to_ `MongoOperations`, not the interface exactly.** Code calls these via
     a `MongoTemplate mongoTemplate` field, so the call target's owner is `MongoTemplate`. A predicate
     that checks `owner.equals("…MongoOperations")` matches **nothing** and the rule silently passes —
     a dangerous false green. Use `owner.isAssignableTo("…MongoOperations")`.
   - **`FreezingArchRule` needs a store, and the first run must be allowed to create it.** Add
     `src/test/resources/archunit.properties` (template provided) with
     `freeze.store.default.allowStoreCreation=true` and `freeze.store.default.path=src/test/resources/frozen`,
     run once to record the existing sites, and commit the generated `frozen/` store.

When a rule fails, it names the class + the missing/mis-annotated field — that's your fix list.

## Behavioural — `AuditVersionMaintainedTest` (template)

`@SpringBootTest` + `@Testcontainers` against `mongo:8`. Adapt the entity/repository names. Asserts:
- `save()` insert → `version == 0`, all audit fields set; `save()` update → `version == 1`, `updated*`
  refreshed, `created*` kept.
- `updateFirst` (caller sets only one field) → `version` auto-incremented by Spring Data **and**
  `updatedAt`/`updatedBy` set by the aspect, `created*` untouched.
- concurrent load-modify-save → `OptimisticLockingFailureException`.

### Testcontainers dependency gotcha (Spring Boot 4.1)
Boot 4.1 manages **Testcontainers 2.x**, whose modules were renamed. Use:
```xml
<dependency><groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-mongodb</artifactId><scope>test</scope></dependency>
```
The artifact ids `junit-jupiter` / `mongodb` (Testcontainers 1.x names) are **404** under Boot 4.1. The
API (`MongoDBContainer`, `@Container`, `@Testcontainers`, `@ServiceConnection`) is unchanged.

## Two assertion traps in the behavioural test
- **BSON millisecond precision.** Mongo stores dates as millis, so an `Instant` read back has lost the
  in-memory value's sub-millisecond nanos. Asserting `loadedCreatedAt.isEqualTo(inMemoryCreatedAt)`
  fails spuriously (`…455Z` ≠ `…455832Z`) — the field *is* preserved, only the assertion is wrong.
  Compare `.truncatedTo(ChronoUnit.MILLIS)` on both sides (the template does this), or assert ordering.
- **The auditor value.** Assert `createdBy`/`updatedBy` are *non-null* unless you set the auditor for the
  test (drive your `CurrentUser`/security context and assert that). Don't assert exact instants — assert
  non-null / ordering, or inject a fixed `Clock`.
