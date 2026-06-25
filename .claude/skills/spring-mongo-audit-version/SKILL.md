---
name: spring-mongo-audit-version
description: >-
  Refactor an existing Spring Boot + Spring Data MongoDB application to add and correctly maintain the
  five cross-cutting persistence fields — @Version (optimistic locking) plus the four audit fields
  createdAt / createdBy / updatedAt / updatedBy — on every write path, going forward with NO bulk
  back-fill of existing data. Use this whenever the user wants to add optimistic locking and/or audit
  fields to MongoDB documents, add @Version to entities, stop audit fields going stale on MongoTemplate
  updates, enforce that every @Document declares these fields, or migrate a Mongo collection to
  versioning without back-filling — even if they don't name all five fields explicitly. Drives the
  mechanical edits with an OpenRewrite recipe and adds AOP + ArchUnit guarantees, touching only the
  code that must change and reporting anything else it notices.
---

# Refactor a Spring Data MongoDB app to add @Version + audit fields (no back-fill)

## What this does

Adds five cross-cutting persistence fields to an existing Spring Data MongoDB app and keeps them
correct on **every** write path:

| Field | Annotation | Purpose |
|-------|-----------|---------|
| `version` (`Long`) | `@org.springframework.data.annotation.Version` | optimistic locking |
| `createdAt` (`Instant`) | `@CreatedDate` | audit |
| `createdBy` (`String`) | `@CreatedBy` | audit |
| `updatedAt` (`Instant`) | `@LastModifiedDate` | audit |
| `updatedBy` (`String`) | `@LastModifiedBy` | audit |

The hard constraint that shapes everything: **no bulk back-fill** — existing documents must not be
mass-updated. Documents gain the fields lazily, as they are next written. Two facts make this work,
and they are the load-bearing knowledge of this whole skill:

1. **`@Version`:** a versioned update filters `{_id, version: <loaded>}`. In MongoDB `{version: null}`
   matches a document where the field is **absent**, but `{version: 0}` does **not**. So a strategy
   that keeps the loaded version `null` lets the first save migrate a legacy doc to `version = 0`;
   any strategy that defaults it to `0` can never match the legacy doc.
2. **Auditing:** Spring Data auditing only fires on the `save()` / entity-conversion path. Every
   `MongoTemplate` update (`updateFirst`, `updateMulti`, `upsert`, `findAndModify`, `findAndReplace`,
   `bulkOps`) bypasses it, so those paths leave audit fields stale unless something injects them.

This skill is backed by a sandbox that verified each claim live against `mongo:8`. When you hit a
situation the references don't cover, **reason from those two rules** rather than refusing — and write
what you found into the report (see step 7). Being precise matters more than being rigid.

## When to use it

Trigger on any of: "add optimistic locking / `@Version`", "add audit fields", "`createdAt`/`createdBy`
keep going stale", "enforce audit fields on our documents", "add versioning to a Mongo collection
without back-filling", "our `updatedBy` is wrong after `MongoTemplate` updates". The user may ask for
only the version half, only the audit half, or both — the workflow below scales down cleanly.

## Scope discipline (important)

**Touch only the code that must change to deliver the five fields and their guarantees.** Do not
reformat, rename, restructure, "tidy", or upgrade unrelated code, even if it looks wrong. When you
notice something out of scope — a likely bug, a risky write path you were told not to change, a
collection that should probably be versioned but the user didn't mention, dead code, a missing test —
**do not fix it. Record it in the report** (step 7) with file:line and why it caught your eye. The
user decides what happens next. This keeps the diff reviewable and the blast radius small.

The edits that ARE in scope: adding the five fields to `@Document` classes; wiring one version
migration strategy; adding the auditing config, the audit-maintaining aspect, and the version guard;
adding the ArchUnit rules and the behavioural test; and fixing tests/call-sites that the new behaviour
legitimately breaks (step 6).

## Workflow

Work through these in order. Each step says when to read a reference for the detail and reasoning.

### 1. Discovery — answer before touching code
These are project-specific and decide everything downstream. **Read `references/discovery.md`** and
answer its checklist with the user / the codebase. The two that change the plan most:
- **Spring Data version.** Behaviour here is verified on Spring Data MongoDB **5.0–5.1** (Spring Boot
  4.0–4.1). On **3.x / SD 4.x**, re-verify the two rules above before relying on them.
- **`_id` assignment, per collection.** Server-assigned `ObjectId` vs client-assigned (a UUID/business
  key set before `save()`). This picks the version strategy in step 3.

Also inventory: which `@Document` classes exist, which already have some of the five fields, every
write path (`save()` sites and each `MongoTemplate` call — especially `bulkOps`/`findAndReplace`),
whether Lombok generates accessors, and whether `@EnableMongoAuditing` is already present.

### 2. Mechanical edits — add the fields with OpenRewrite
Adding annotated fields + imports (+ accessors) to every `@Document` is the part that scales with the
number of entities, so it is automated. **Read `references/openrewrite.md`** for the exact build/run
commands. In short: build the bundled recipe module (`assets/rewrite-recipes/`), wire it into the
target's `rewrite-maven-plugin`, and run `com.example.rewrite.AddMongoAuditAndVersionFields` (or the
audit-only / version-only variants). The recipe adds only missing fields and is idempotent; it leaves
existing fields untouched and skips accessors when Lombok is present.

If the user's build can't run OpenRewrite (e.g. a Gradle setup the recipe module isn't wired for, or a
locked-down CI), fall back to making the *same* edits by hand — the recipe defines exactly what "done"
looks like. Don't block the refactor on the tool.

### 3. Version strategy — wire optimistic locking without back-fill
`@Version` alone breaks `save()` on legacy version-less docs (duplicate key). Pick the strategy by how
ids are assigned, then wire it. **Read `references/version-strategy.md`** for the decision tree, the
exact code, and the gotchas (the id+version boundary; the first migrating write not being locked).
- **Server-assigned ids →** make each entity implement `Persistable<ID>` with `isNew() { return id ==
  null; }`. Smallest change. (This is the one entity edit OpenRewrite does *not* do for you — apply it
  from the reference.)
- **Client-assigned ids (id set before save) →** add the custom `UpsertMongoRepository` base class +
  `@EnableMongoRepositories(repositoryBaseClass = …)` from `assets/templates/`. No per-entity change.

### 4. Audit maintenance — make the fields correct on every write
Standard auditing only covers `save()`. **Read `references/audit-hybrid.md`**. Wire all three layers:
- `@EnableMongoAuditing` + an `AuditorAware<String>` for the `save()` path (add from `assets/templates/`
  if not already present; the auditor's user comes from the security context — here a request header).
- The **`AuditFieldsAspect`** (`assets/templates/`) injects `updatedAt/By` (`$set`) and `createdAt/By`
  (`$setOnInsert`) on the `MongoTemplate` update methods, adding only what the caller didn't set.
- It cannot fix `findAndReplace` (resets created fields) or `bulkOps` (separate builder) — those are
  banned structurally in step 5.

### 5. Guardrails — ArchUnit so it can't silently regress
**Read `references/tests-and-guardrails.md`**. Add the ArchUnit rules (`assets/templates/`): every
`@Document` declares the five fields with the right annotations; `findAndReplace`/`bulkOps` are not
used on audited/versioned entities (grandfather existing sites if needed, block new ones). ArchUnit
proves the fields *exist*; the aspect proves they're *filled* — you need both. Also add the version
guard aspect (`assets/templates/`) that WARNs on the two write paths Spring Data doesn't maintain.

### 6. Validate — run the suite, fix the fallout correctly
Turning on `@Version`/auditing makes some passing tests fail. **Read `references/breakage-catalog.md`**
to classify each failure and apply the right fix: 🧪 a test that encoded a now-false assumption →
rewrite the test; 🐛 a real bug the refactor surfaced (a concurrent lost update now throws
`OptimisticLockingFailureException`; a `findAndReplace` that drops the version) → fix the code, never
silence the exception; 🔧 an expectation that legitimately changed → update it. Add the behavioural
test from `assets/templates/` (Testcontainers) that proves version + audit are maintained across the
write paths. `OptimisticLockingFailureException` from concurrency and `DuplicateKeyException` on legacy
data are 🐛 until proven otherwise.

### 7. Report — what you changed and what you flagged
Always finish with a report. **Use the structure in `references/report-template.md`**: the strategy you
chose and why; every file you changed grouped by purpose; the breakages you fixed (and their kind);
and — the part that protects the user — **everything you noticed but deliberately did NOT touch**
(out-of-scope smells, unversioned collections the user didn't mention, risky write paths, untested
paths), each with file:line and a one-line reason. If you departed from the references because the
project didn't fit, say so here.

## How the pieces fit

```
discovery ─► OpenRewrite recipe (fields)         ─┐
            + version strategy (Persistable/upsert)├─► ArchUnit (structure) + Aspect (behaviour)
            + auditing config + audit aspect       ─┘        │
                                                              ▼
                                              run tests ─► fix breakage (catalog) ─► report
```

## Reference map

| File | Read it for |
|------|-------------|
| `references/discovery.md` | the Phase-0 questions that decide the plan |
| `references/version-strategy.md` | the `{version:null}` rule, custom-isnew vs upsert-all-cases, the entity code, gotchas |
| `references/audit-hybrid.md` | why auditing misses template writes; the aspect; the three failure modes |
| `references/write-paths.md` | which write paths maintain `@Version` / audit natively, and the two gaps |
| `references/openrewrite.md` | build + run the recipe; what it does and doesn't do; manual fallback |
| `references/tests-and-guardrails.md` | the ArchUnit rules + the behavioural (Testcontainers) test |
| `references/breakage-catalog.md` | what breaks when you flip it on, classified, with the fix per kind |
| `references/report-template.md` | the required final report structure |
| `assets/rewrite-recipes/` | the OpenRewrite recipe module (the mechanical edits) |
| `assets/templates/` | drop-in: aspects, auditing config, upsert repo, ArchUnit + behavioural tests |
