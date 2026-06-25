# archunit/enforce-audit

Path 1 of the audit problem (see `AUDIT.md` on `main`): ArchUnit rules that enforce audit-field
discipline at build time.

## The rule

`AuditFieldsArchTest.everyDocumentDeclaresAllFourAuditFields` — every `@Document` entity must declare
`createdAt`/`createdBy`/`updatedAt`/`updatedBy`, each with the matching Spring Data annotation
(`@CreatedDate`/`@CreatedBy`/`@LastModifiedDate`/`@LastModifiedBy`). Dependency: `archunit-junit5`.

**Demonstrated:** `Customer` shipped with only the timestamps. The rule failed the build —

```
Rule '... should declare createdAt/By + updatedAt/By ...' was violated (2 times):
  Customer is missing audit field 'createdBy'
  Customer is missing audit field 'updatedBy'
```

— and the fix is to add the fields to `Customer` (now green, alongside compliant `Product`/`Order`).
That is the "bigger code impact" of this path: every pre-existing entity must be brought up to standard.

## What ArchUnit can and can't do (the key comparison point)

- ✅ **Structural** — enforce that entities *have* the four fields. Catches "some classes don't have
  them all."
- ✅ **Banning** — forbid the operations that can't be safely audited. A complementary rule:

  ```java
  noClasses().should().callMethodWhere(callTo("findAndReplace") or "bulkOps" on MongoOperations)
  ```

  Because the sandbox legitimately demos those, wrap it in `FreezingArchRule.freeze(rule)` — it
  records today's call sites as an accepted baseline and only fails on **new** usage. That is the
  "going forward, no back-fill" model applied to code.
- ❌ **Behavioural** — ArchUnit canNOT verify that an arbitrary `updateFirst(query, update)` actually
  sets `updatedAt`/`updatedBy`. That data-flow guarantee is static-analysis-hard and is exactly what
  the `aop/auto-audit` aspect provides at runtime.

## Recommended hybrid

| Concern | Tool |
|---------|------|
| Entities *have* the four fields | **ArchUnit** rule (this branch) |
| Unsafe ops (`findAndReplace`/`bulkOps`) not used on audited types | **ArchUnit** freeze-rule |
| Updates *maintain* the fields on every template write | **AOP** aspect (`aop/auto-audit`) |
| `save()` path | Spring Data auditing (built-in) |

ArchUnit guarantees the shape; AOP guarantees the behaviour. Neither alone is sufficient.
