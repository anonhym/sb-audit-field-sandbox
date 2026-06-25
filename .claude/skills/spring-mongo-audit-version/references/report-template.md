# Final report — required structure

Always end the refactor with this report. Its most important section is the last one: **what you
noticed but deliberately did not touch.** That's how the user keeps control of scope and catches risks
the refactor surfaced but shouldn't silently fix. Keep it concrete — file:line, not vibes.

Use this template:

```markdown
# Mongo audit/version refactor — report

## Summary
- Collections touched: <list>
- Version strategy chosen: <custom-isnew | upsert-all-cases | per-collection mix> — because <id assignment>
- Spring Data MongoDB version: <x.y> (behaviour <verified here | re-verify needed for 3.x/4.x>)
- Auditing: <added @EnableMongoAuditing | already present>; auditor source: <security context | CurrentUser/X-User>

## Changes made (grouped by purpose)
### Fields added (OpenRewrite — `AddMongoAuditAndVersionFields`)
- <Entity>.java — added <version / createdAt / createdBy / updatedAt / updatedBy>  (others already present)
- ...
### Version strategy
- <Entity> implements Persistable<…> + isNew()    | or: UpsertMongoRepository + MongoRepositoriesConfig
### Audit maintenance
- AuditingConfig (+ AuditorAware), AuditFieldsAspect, VersionGuardAspect
### Guardrails & tests
- AuditAndVersionArchTest (rules: <which>); AuditVersionMaintainedTest
- Dependencies added: org.aspectj:aspectjweaver; archunit-junit5 (test); testcontainers-* (test)
### Breakage fixed (from breakage-catalog.md)
- <test/file> — <V#/A#> <kind 🧪/🐛/🔧> — <what changed and why>

## Flagged — NOT changed (out of scope; your call)
> I noticed these while refactoring but left them alone. Each is a deliberate non-change.
- <file:line> — <what> — <why it caught my eye / suggested follow-up>
- e.g. OrderArchiveJob.java:88 — uses bulkOps on `orders` (now versioned/audited); maintains neither.
  Left as-is (you said touch only what's needed). Suggest: add .inc("version",1) + audit fields, or move off bulkOps.
- e.g. `payments` collection (@Document Payment) is written by PaymentService but you only asked about
  orders/products — it has none of the five fields. Not touched.
- e.g. ReactiveMongoTemplate used in NotificationService — this skill's behaviour is verified for the
  sync template only; reactive needs separate verification.

## Verification run
- `mvn compile`: <result>
- test suite: <pass/fail counts; which failures were fixed and how>
- behavioural test against mongo:8: <result>

## Rollout reminders (not done by this refactor)
- Every writer of each touched collection must be on the new code before the guarantees hold.
- Migration is lazy (docs gain fields on next write); no back-fill was performed.
- Instrument the OptimisticLockingFailureException rate + field coverage before/through rollout.
- Test the rolling-deploy window (old + new instances writing the same collection).
```

## Why the "Flagged — NOT changed" section matters
The instruction is to touch only what must change. But a refactor *sees* a lot — other unversioned
collections, risky `bulkOps`/`findAndReplace` sites, reactive paths, dead code, missing tests. Silently
fixing them blows the scope and the diff; silently ignoring them wastes what you learned. Recording them
with file:line + a one-line reason gives the user a punch-list and keeps you honest. If you departed
from the references because the project didn't fit, explain it here too.
