# Discovery — answer these before touching code

These are project-specific and decide the plan. Gather them from the user and the codebase first; a
wrong assumption here causes the wrong strategy downstream.

## The checklist

1. **Spring Data MongoDB version.** The two load-bearing behaviours (template updates auto-incrementing
   `@Version`; `{version:null}` matching an absent field) are verified on **Spring Data MongoDB
   5.0–5.1** (Spring Boot 4.0–4.1). On **Spring Boot 3.x / Spring Data 4.x** they are *probably* the
   same but were not verified here — re-verify against the actual version before relying on them. Find
   it: the `spring-boot-starter-parent` version in the POM, or `mvn dependency:tree | grep spring-data-mongodb`.

2. **`_id` assignment, per collection.** This picks the version strategy:
   - **Server-assigned** — `@Id String/ObjectId` left null on new entities, Mongo fills it. → custom-isnew.
   - **Client-assigned** — id is set before `save()` (a UUID, a business key). → upsert-all-cases.
   - Check the create paths: does any code do `entity.setId(...)` before `save()`, or `new Entity(uuid, …)`?
     If *any* collection ever sets ids client-side, that collection needs the upsert strategy.

3. **Sync vs reactive template.** This skill is verified for the **synchronous** `MongoTemplate` /
   `MongoOperations`. Reactive (`ReactiveMongoTemplate`) uses different callbacks and needs separate
   verification — flag it in the report if present.

4. **Every writer of each collection.** List every service and background job that writes each
   collection. A writer left on old code silently bypasses the new version/audit guarantees *for
   everyone*. This drives the rollout, and it's the most commonly missed risk.

5. **Current data state.** Roughly how many documents lack `version` / the audit fields, and is there
   any partial state from a previous attempt? (No back-fill — this is just to size the lazy migration
   and set expectations.)

6. **Write-path inventory.** Find every write:
   - `save()` / `saveAll()` / `insert()` call sites.
   - Every `MongoTemplate` update: `updateFirst`, `updateMulti`, `upsert`, `findAndModify`,
     `findAndReplace`, `bulkOps`, and pipeline (`AggregationUpdate`) updates.
   - `findAndReplace` and `bulkOps` are the dangerous ones (they don't maintain version/audit) — note
     each site; you'll remediate or flag them.
   - Quick grep: `rg "updateFirst|updateMulti|findAndModify|findAndReplace|bulkOps|aggregateUpdate"`.

## Also detect

- **Is `@EnableMongoAuditing` already present?** If yes, the save() path is already audited — don't add
  a second one. If no, you'll add `AuditingConfig`.
- **Lombok?** If entities use `@Data`/`@Getter`/`@Setter`/`@Value`, the OpenRewrite recipe skips
  generating accessors (Lombok makes them). Confirm so you don't end up with duplicates.
- **Existing fields.** Which `@Document` classes already have some of the five fields (and with which
  annotations)? The recipe adds only what's missing; an existing field with a *wrong* or missing
  annotation is something to flag/fix, not silently overwrite.
- **AOP availability.** Spring Boot 4 removed `spring-boot-starter-aop`; you add `org.aspectj:aspectjweaver`
  (then `AopAutoConfiguration` turns on `@EnableAspectJAutoProxy`). Spring Boot 3 can use the starter.

## Output of this phase
A short written summary: Spring Data version; per-collection id assignment (→ chosen strategy); the
list of `@Document` classes with which of the five fields each already has; the write-path inventory
with the `findAndReplace`/`bulkOps` sites called out; whether auditing/Lombok/AOP are already in place;
and the set of writers per collection. This feeds every later step and the final report.
