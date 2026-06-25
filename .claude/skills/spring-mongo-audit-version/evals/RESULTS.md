# Eval results — how this skill was validated

The skill was tested the way the sandbox tests everything: on a branch, against a real `mongo:8`.

## Setup
- **Test bed:** branch `skill-test/legacy-app` — the sandbox app in a realistic "before" state:
  `Product` (`@Document`, four audit fields wired, **no `@Version`**), a bare `Order` (`@Document`, none
  of the five fields), server-assigned ids, `@EnableMongoAuditing` already present for the `save()` path.
- **Runs:** two independent Claude agents each refactored an isolated git worktree off that branch —
  one **with** the skill (`skill-test/eval-withskill`), one **without** it (baseline,
  `skill-test/eval-baseline`) — given the same task: add the five fields maintained on every write path,
  no back-fill, with build-time + runtime guarantees, touching only what's needed.

## With-skill run — PASS (independently re-verified)
`./mvnw clean compile` → success; `./mvnw test` → **BUILD SUCCESS, 7/7 green** (re-run by the skill
author in the same worktree to confirm): `AuditAndVersionArchTest` 3/3, `AuditVersionMaintainedTest`
3/3 (Testcontainers `mongo:8`), context-load 1/1.

Following only the skill, the agent:
- chose **custom-isnew** (server-assigned ids), and applied the `Persistable`/`isNew()` edit by hand —
  the one entity change the OpenRewrite recipe deliberately doesn't do;
- ran the bundled recipe (`AddMongoAuditAndVersionFields`) for the fields — adding all five to `Order`
  and **only `@Version`** to the already-audited `Product` (idempotent, as designed);
- added the `AuditFieldsAspect` + `VersionGuardAspect` + `aspectjweaver`; **reused** the existing
  auditing config instead of duplicating it;
- added the ArchUnit rules (with `FreezingArchRule` grandfathering the two existing
  `findAndReplace`/`bulkOps` sites) and the Testcontainers behavioural test;
- fixed the test fallout per `breakage-catalog.md` (an A1 BSON-precision assertion), never silencing it;
- produced the required report, including a precise **Flagged — NOT changed** list (the
  `findAndReplace`/`bulkOps`/pipeline gaps in `MongoTemplateService`, the client-set-id edge case, a
  redundant manual `updatedAt`, the `java.version` mismatch) — exactly the scope discipline the skill asks for.

### Three template defects the run surfaced (now fixed in this skill)
1. **ArchUnit rule-3 false green:** the `findAndReplace`/`bulkOps` ban matched owner `MongoOperations`
   exactly, but apps call via a `MongoTemplate` field, so the rule matched nothing. Fixed to match owners
   *assignable to* `MongoOperations`.
2. **`FreezingArchRule` store:** needs `freeze.store.default.allowStoreCreation=true` on first run —
   added an `archunit.properties` template and documented it.
3. **BSON millisecond precision:** the behavioural test compared a Mongo-roundtripped `Instant` to an
   in-memory one and failed on sub-millisecond nanos. Fixed to compare `truncatedTo(MILLIS)`.

## Baseline (no-skill) run — also green, and it hardened the skill
Same task, no skill. The baseline also reached a compiling, green state — `./mvnw test` **9/9** (ArchUnit
2/2, Testcontainers behavioural 6/6, context-load 1/1) — but it took the **heavier** path: it weighted
the one client-set-id call site (`ExperimentService.saveNewWithPresetId`, an experiment probe) as
requiring the general strategy and hand-rolled a custom **upsert-all-cases** repository, where the
with-skill run chose the smaller `Persistable`/custom-isnew change and *flagged* that same probe as the
edge case. Both are defensible reads of an ambiguous situation — which is the point: the skill encodes
the decision but lets the agent reason about it.

More valuable: building upsert-all-cases **with audit fields** for the first time, the baseline found a
real gap that the original (pre-audit) sandbox never exercised — the custom `replace` path bypasses
Spring Data auditing, and `@Created*` must be set *before* the non-null version or auditing-by-`isNew`
skips it. Its validated fix (9/9) was folded back into this skill's `UpsertMongoRepository` template and
documented in `references/version-strategy.md`. So both arms of the eval improved the skill.

## Takeaway
An independent agent, following only the skill, produced a correct, compiling, fully-tested refactor
with the right strategy and disciplined scope on the first pass — and the exercise hardened the skill's
own templates. That's the bar this skill is built to clear.
