# sb-audit-field-sandbox

A Spring Boot **4.1** + **Spring Data MongoDB** sandbox that worked out — and **verified live** — how
to safely refactor existing production collections for two cross-cutting concerns, **without a bulk
back-fill** (data going forward only):

1. **Optimistic locking** — add `@Version` to collections whose documents have no `version` field.
2. **Audit fields** — keep `createdAt/createdBy/updatedAt/updatedBy` correct on *every* write path.

It is built to be a **knowledge base for a refactor skill / agent**: every branch is a small, isolated,
runnable proof of one idea, and the docs capture the *reasoning* and the *measured* outcome — not just
the final code. Each finding below was run against a real `mongo:8`.

> **Start here for the refactor:** [`MIGRATION-PLAN.md`](MIGRATION-PLAN.md) — the synthesized plan,
> decision trees, and gotchas. The per-topic docs and branches below are its evidence.

- **Java** 21 · **Build** Maven (`./mvnw`) · **Mongo** auto-started via Spring Boot Docker Compose
- Verified identical on **Spring Boot 4.0.7** (Spring Data 5.0.6) **and 4.1.0** (5.1.0).

## TL;DR findings

**Version (no back-fill):**
- Naively adding `@Version` breaks: a version-less doc loads as `version = null` → treated as new →
  insert → duplicate-key. The deciding rule is a query-match detail: `{version: null}` matches a doc
  with the field **absent**, `{version: 0}` does **not**.
- **Server-assigned ids →** `custom-isnew` (entity `implements Persistable`, `isNew = id==null`). Smallest change.
- **Client-assigned ids →** `upsert-all-cases` (custom repo `save()` → `replaceOne(_id, upsert)` on the first write). Only one that also handles a new doc whose id is pre-set.
- Spring Data 5.x **already auto-increments `@Version`** on `updateFirst/updateMulti/upsert/findAndModify` + pipeline updates. The only gaps are **`bulkOps`** and **`findAndReplace`**.

**Audit (no back-fill):**
- Spring Data auditing fires **only on `save()`**. Every `MongoTemplate` update leaves the audit
  fields stale/forgotten (and `findAndReplace` *resets* the created fields).
- Fix is a **hybrid**: AOP injects the fields on template writes (behaviour) + ArchUnit enforces that
  entities *declare* them and bans the two unsafe ops (structure).

## Repo map (branches)

| Group | Branch | Demonstrates |
|-------|--------|--------------|
| **baseline** | `main` | no `@Version`; audit fields + `@EnableMongoAuditing`; shared harness |
| **version: pick a strategy** | `approach/naive` | add `@Version` only → fails (control) |
| | `approach/lazy-on-read` | `AfterConvertCallback` default → still fails |
| | `approach/explicit-upsert` | `replaceOne(_id)` → works (caveat) |
| | `approach/custom-isnew` | `Persistable` → **recommended (server ids)** |
| | `approach/upsert-all-cases` | `upsert(firstWrite)` → **recommended (client ids); covers all save shapes** |
| | `approach/version-with-backfill` | reference: the forbidden bulk back-fill |
| **version: maintain on writes** | `feature/version-guard` | proof Spring Data auto-increments template updates; aspect warns on `bulkOps`/`findAndReplace` |
| **boot compatibility** | `compat/sb40-custom-isnew`, `compat/sb40-upsert-all-cases` | identical behaviour on Boot 4.0.7 |
| **audit: solutions** | `aop/auto-audit` | aspect maintains the 4 fields on template writes |
| | `archunit/enforce-audit` | build-time rule: every `@Document` declares the 4 fields |

## Documentation map

| Doc | What it covers |
|-----|----------------|
| [`MIGRATION-PLAN.md`](MIGRATION-PLAN.md) | **The refactor knowledge base** — plan, decision trees, gotchas (read first) |
| [`APPROACHES.md`](APPROACHES.md) | Version strategies: the matrix, the `{version:null}` insight, the recommendation |
| [`VERSION-WRITES.md`](VERSION-WRITES.md) | Which write paths maintain `@Version` (Spring Data 5.x) + the two gaps |
| [`AUDIT.md`](AUDIT.md) | Audit baseline: the three failure modes + the AOP/ArchUnit paths |
| [`report.html`](report.html) | Visual report of the **version** investigation (open in a browser) |
| each branch's `NOTES.md` | the hypothesis + measured transcript for that branch |

## Running / exploring

```bash
./mvnw spring-boot:run        # Docker must be running; mongo:8 auto-starts
```
Then drive the experiments with [`requests.http`](requests.http) or `curl`. The harness reads version
and audit fields from the **stored BSON**, so the same endpoints work on every branch.

> Docker Compose support is dev-time only (excluded from the fat jar). To run the jar, point it at
> Mongo: `SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/sandbox`.

**Key endpoints:** `/api/products` (CRUD), `/api/experiments` (legacy-doc, load-then-save,
concurrent-update, save-new-with-id[-and-version], inc-stock, raise-prices, reset),
`/api/updates` (upsert, find-and-modify, find-and-replace, bulk, pipeline, array ops),
`/api/status` (version-stats, count-by-category, audit/{id}). Audit user is set per request via the
`X-User` header.

## Project layout

```
src/main/java/com/example/versionsandbox/
├─ domain/        Product (audit fields), Customer, Order
├─ repository/    ProductRepository (MongoRepository + derived queries)
├─ service/       ProductService (CRUD), MongoTemplateService (full update surface), ExperimentService (probes)
├─ web/           Product/Experiment/Status/UpdateOps controllers + ApiExceptionHandler
├─ config/        AuditingConfig, CurrentUser(+Filter)  [+ strategy/aspect classes on the relevant branches]
└─ seed/          DataSeeder
```
