# Migration plan & knowledge base

How to add **optimistic locking (`@Version`)** and **audit fields** to existing production MongoDB
collections, **without a bulk back-fill** (going forward only). Every claim here was verified live in
this sandbox; the branches named throughout are the runnable evidence.

This doc is written to be consumed by a refactor skill/agent **and** a human. It is split into:
discovery → version workstream → audit workstream → cross-cutting concerns → phased rollout →
checklists. The *thinking* is captured alongside each decision, not just the answer.

---

## Phase 0 — Discovery (must answer before touching code)

These are prod-specific and decide everything downstream. A refactor skill should gather them first.

1. **Spring Data MongoDB version.** Behaviour verified identical on Boot **4.0.7** (SD 5.0.6) and
   **4.1.0** (SD 5.1.0). On Boot **3.x** (SD 4.x) the two behaviours below must be re-verified before
   relying on them: (a) template updates auto-incrementing `@Version`, (b) `{version:null}` matching an
   absent field.
2. **`_id` assignment, per collection.** Server-assigned `ObjectId` vs client-assigned (UUID/business
   key set before `save()`). This picks the version strategy.
3. **Sync vs reactive** Mongo template. This sandbox is synchronous; reactive callbacks differ and
   need separate verification.
4. **Who writes each collection.** Every service/job. A writer left on old code silently bypasses the
   new guarantees for everyone (see cross-cutting).
5. **Current state of the data.** How many docs lack `version` / the audit fields; any partial state.
6. **Inventory the write paths** in code: `save()` sites, and every `MongoTemplate` update — especially
   `bulkOps` and `findAndReplace`.

---

## Workstream A — Optimistic locking (`@Version`)

### The problem
Adding `@Version` naively breaks existing data: a version-less doc loads as `version = null`, Spring
Data treats it as new → `save()` inserts → existing `_id` → duplicate-key error. The usual fix (bulk
`$set version=0`) is **forbidden** here.

**Root cause (the one rule that explains everything):** a versioned update filters `{_id, version:N}`.
In MongoDB `{version: null}` matches a doc where the field is **absent**; `{version: 0}` does **not**.

### Decision tree
```
Collection already fully versioned?  → nothing to do (standard @Version).
Version-less docs exist + back-fill forbidden:
    ids server-assigned (ObjectId)?      → custom-isnew          [approach/custom-isnew]
    ids ever client-assigned (preset)?   → upsert-all-cases      [approach/upsert-all-cases]
Back-fill allowed after all?             → bulk $set version=0, then @Version  [approach/version-with-backfill]
```

### Verified solutions
- **`custom-isnew`** (recommended, server ids) — entity implements `Persistable`, `isNew()=id==null`.
  The loaded version stays `null`, so the update filter `{_id, version:null}` matches the legacy doc →
  it migrates to `version 0` on first save; locking intact from the first write. One interface, no
  extra beans.
- **`upsert-all-cases`** (recommended, client-assigned ids) — a custom repository base class whose
  `save()` does `replaceOne({_id}, upsert=true)` for the version-null *first write* (replaces a legacy
  doc OR inserts a new client-id doc), and a version-checked `replaceOne` afterwards. Wired with
  `@EnableMongoRepositories(repositoryBaseClass = …)`. The only strategy that also handles a **new
  doc with a preset id**.
- **Failures to avoid:** `approach/naive` (duplicate-key) and `approach/lazy-on-read`
  (`AfterConvertCallback` default to `0` → trades duplicate-key for an optimistic-lock error). See
  `APPROACHES.md`.

### Maintaining the version on writes
Spring Data 5.x **auto-increments `@Version`** on `updateFirst`/`updateMulti`/`upsert`/`findAndModify`
and pipeline updates — no interceptor needed. The only gaps: **`bulkOps`** (add `.inc("version",1)`
yourself) and **`findAndReplace`** (drops the version unless the replacement carries it; prefer
`save()`/`findAndModify`). Optionally warn via an aspect (`feature/version-guard`) or ban via ArchUnit.
Full matrix in `VERSION-WRITES.md`.

### Gotchas
- **New entity with id + non-null version** (doc doesn't exist) → `OptimisticLockingFailureException`,
  not an insert — by the `@Version` contract, identical on every strategy. New entities must carry a
  `null` version. For migration/replication that must preserve a version, use a direct
  `mongoTemplate.insert(...)`, not `save()`.
- **First migrating write of a legacy doc is not optimistically locked** (no prior version to check);
  locking applies from the next write. Two writers racing the same not-yet-versioned doc can both win
  once; concurrent create of the same client id can raise `DuplicateKeyException` (retry).
- **`_id` type consistency** — Spring Data stores 24-hex `@Id String` as `ObjectId`; raw inserts must
  use `ObjectId` too or `findById(hex)` misses them.
- **New failure mode** — `OptimisticLockingFailureException` didn't exist under last-write-wins; add
  retry/surface handling at load-modify-save call sites.

---

## Workstream B — Audit fields (`createdAt/By`, `updatedAt/By`)

### The problem
With `@EnableMongoAuditing`, the four fields populate automatically — **but only on the `save()` /
entity-conversion path.** Every `MongoTemplate` update bypasses auditing. Measured failure modes
(`AUDIT.md`): `updatedBy` forgotten on every template update, `createdBy/updatedBy` null on
template `upsert`-insert, and **`findAndReplace` resets `createdAt/createdBy`** (data loss).

### The solution is a hybrid (not either/or)
| Concern | Tool | Branch |
|---------|------|--------|
| `save()` path | Spring Data `@EnableMongoAuditing` + `AuditorAware` | built-in |
| Template writes *maintain* the fields | **AOP aspect** — inject `updatedAt/By` (`$set`) + `createdAt/By` (`$setOnInsert`), adding only what's missing | `aop/auto-audit` |
| Entities *declare* all four fields | **ArchUnit** rule over `@Document` classes | `archunit/enforce-audit` |
| `findAndReplace`/`bulkOps` not used on audited types | **ArchUnit** freeze-rule (grandfather existing, block new) | `archunit/enforce-audit` (described) |

**Why both:** ArchUnit proves the fields *exist* (structural) but cannot prove code *fills* them; AOP
fills them (behavioural) but can't force entities to declare them or fix `findAndReplace`/`bulkOps`.
Neither alone is complete.

### Gotchas
- `findAndReplace` triggers auditing but treats the replacement as new → **resets created**. AOP can't
  fix this safely → **ban it on audited entities** (ArchUnit). Prefer `save()`/`findAndModify`.
- Template `upsert`-insert leaves the `By` fields null → the AOP aspect's `$setOnInsert created*`
  covers it.
- The auditor (`createdBy/updatedBy`) needs a source — a request-scoped/thread-local "current user"
  (here: `X-User` header → `CurrentUser` → `AuditorAware`). In prod, the security context.

---

## Cross-cutting concerns (both workstreams)

- **Rolling-deploy window.** During deploy, old instances (no `@Version` / no template auditing) and
  new instances write the *same* collection. Old writes bypass the new guarantees. Mitigate: deploy all
  writers of a collection together, or gate the new behaviour behind a flag, and **test this window
  explicitly**.
- **Every writer must adopt.** All services/jobs writing a collection must use the strategy, or the
  version/audit guarantees break for everyone. Coordinate per collection.
- **Migration is lazy / per-document.** Docs gain `version`/audit fields as they're next written. Cold
  docs stay as-is indefinitely (functionally fine). Optionally converge with a controlled paginated
  read-then-save *trickle* (still no bulk `$set`).
- **Code-only migration** — no DB script, no index change (`@Version` needs no index).
- **Observability first** — metrics/alerts for `OptimisticLockingFailureException` rate, version/audit
  field coverage over time, and aspect/guard warnings — before rollout.
- **Rollback is safe** — removing the `@Version`/audit code is harmless; already-written `version`/audit
  fields don't hurt anything.

---

## Phased rollout (both workstreams)

| Phase | Steps |
|-------|-------|
| **0 Discovery** | the six items above; pick a version strategy per collection |
| **1 Implement** | add `@Version` (+ chosen strategy) and the 4 audit fields + `@EnableMongoAuditing`; add the AOP audit aspect; remediate `bulkOps`/`findAndReplace`; add OLFE handling at call sites |
| **2 Validate** | run the existing suite; fix the fallout using [`REFACTOR-SCENARIOS.md`](REFACTOR-SCENARIOS.md) (test bug vs real bug); port the sandbox probes to staging on prod-like data (legacy migrate, concurrent lock, client-set-id, id+version boundary, audit per write path); **test the rolling-deploy window** |
| **3 Rollout** | instrument first, deploy (lazy migration), watch OLFE rate + field coverage |
| **4 Closeout** | document rollback; decide cold-doc convergence (accept lazy, or trickle) |

---

## Quick checklists

**Per collection (version):**
- [ ] ids server- or client-assigned? → `custom-isnew` / `upsert-all-cases`
- [ ] `@Version Long` (nullable) added; strategy wired
- [ ] `bulkOps` updates add `.inc("version",1)`; `findAndReplace` removed/handled
- [ ] no entity constructed with id + non-null version on a new doc
- [ ] OLFE retry/handling at load-modify-save sites
- [ ] all writers of the collection on the new code

**Per entity (audit):**
- [ ] declares `createdAt/By`, `updatedAt/By` with the audit annotations (ArchUnit enforces)
- [ ] `@EnableMongoAuditing` + `AuditorAware` present
- [ ] AOP audit aspect active for template writes
- [ ] `findAndReplace`/`bulkOps` not used on the entity (ArchUnit bans)

**Runtime (before relying on it):**
- [ ] Spring Data version's `@Version` + auditing behaviour verified (≥5.0 ok; 4.x re-check)
- [ ] sync vs reactive confirmed
- [ ] rolling-deploy window tested
