# aop/auto-audit

Path 2 of the audit problem (see `AUDIT.md` on `main`): an AOP aspect that maintains the four audit
fields on the `MongoTemplate` write paths Spring Data auditing doesn't cover — "add what's missing",
going forward, no back-fill.

## Mechanism

`config/AuditFieldsAspect` (needs `aspectjweaver`; `spring-boot-starter-aop` was removed in Boot 4).
For an audited entity (one whose mapping has `updatedAt` + `updatedBy` properties):

- `updateFirst` / `updateMulti` / `upsert` / `findAndModify` → inject into the `Update`, only when the
  caller didn't already set the field:
  - `$set updatedAt`, `$set updatedBy` (every write)
  - `$setOnInsert createdAt`, `$setOnInsert createdBy` (insert branch only)
- `findAndReplace` / `bulkOps` → **WARN** and proceed. These can't be safely fixed from a simple
  aspect (`findAndReplace` resets the created fields; `bulkOps` registers updates on a separate
  builder). Ban them on audited entities via ArchUnit (`archunit/*` branch) — that's the hybrid.

The auditor (`createdBy`/`updatedBy`) comes from `CurrentUser`, same as the `save()`-path auditing.

## Observed (live, vs the `main` baseline)

| Write path | baseline `updatedBy` | with aspect |
|------------|----------------------|-------------|
| `save()` insert/update | maintained | maintained (auditing) |
| `updateFirst` / `findAndModify` / `updateMulti` / `upsert` (update) | **stale** | **current user** ✅ |
| `upsert` (insert) | `createdBy`/`updatedBy` **null** | both set to current user ✅ |
| `findAndReplace` | created **reset** | created **still reset** → WARN ⚠️ |
| `bulkOps` | not maintained | WARN ⚠️ |

"Add what's missing" confirmed: the aspect respected a manually-set `updatedAt` and injected the
forgotten `updatedBy`.

## Verify

```
./mvnw spring-boot:run
POST /api/products (X-User: alice)              -> all 4 = alice
POST /api/experiments/{id}/inc-stock (X-User: bob) -> updatedBy now = bob (was stale)
GET  /api/status/audit/{id}
```
