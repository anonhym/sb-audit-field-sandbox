# Version strategy — add `@Version` with no back-fill

## The one rule that explains everything

When Spring Data updates a versioned entity it issues `updateFirst({_id, version: <loaded>}, …)`. The
deciding fact is a MongoDB equality-match detail:

- **`{version: null}` matches a document whose `version` field is absent** (null == missing).
- **`{version: 0}` does NOT** — it only matches a stored `0`.

So a legacy version-less document can be migrated *in place on its next save* only if the loaded
version stays `null` (the filter then matches it and stamps `version = 0`). Any approach that defaults
the loaded version to `0` produces a filter that misses the legacy doc → "0 documents modified" →
`OptimisticLockingFailureException`. There is **no** non-null default that works, which is why an
`AfterConvertCallback` that sets `version = 0` on read is a dead end under the no-back-fill constraint.

## Why naive `@Version` breaks

Add `@Version` alone and `save()` of a loaded legacy doc throws `DuplicateKeyException`: the loaded
version is `null`, Spring Data's default `isNew` treats a null version as "new" → it tries to *insert*
→ the `_id` already exists → E11000. The strategy's whole job is to stop that misclassification.

## Decision tree

```
Collection already fully versioned?            → nothing to do (standard @Version).
Version-less docs exist, back-fill forbidden:
    ids server-assigned (ObjectId)?            → custom-isnew      (entity implements Persistable)
    ids ever client-assigned (set pre-save)?   → upsert-all-cases  (custom repository base class)
Back-fill allowed after all?                   → bulk $set version=0, then plain @Version (not this skill)
```

## Strategy A — custom-isnew (server-assigned ids) — RECOMMENDED, smallest change

Make the entity implement `Persistable<ID>` so "new" is decided by the **id**, not the version. The
loaded version stays `null`, the update filter is `{_id, version:null}`, it matches the legacy doc, and
the first save stamps `version = 0`; locking is intact from the first write. **This is the one entity
edit the OpenRewrite recipe does not do for you — apply it by hand** (the recipe adds the `version`
field; you add the interface + `isNew()`).

```java
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "products")
public class Product implements Persistable<String> {

    @Id private String id;
    @Version private Long version;
    // ... other fields ...

    @Override public String getId() { return id; }

    /** An entity is new only when it has no id yet; a null version no longer means "new". */
    @Override public boolean isNew() { return id == null; }
}
```

Notes:
- Spring Data MongoDB uses **field access**, so the no-arg `isNew()` is not treated as a persistent
  property and is never written to the document.
- If `getId()` already exists, just add `@Override` and the `implements` clause + `isNew()`.
- One interface, no extra beans, no custom repository.

## Strategy B — upsert-all-cases (client-assigned ids) — covers every save shape

If ids can be set *before* `save()`, custom-isnew mis-treats a brand-new pre-id'd document as an update
and throws. Use the custom repository base class instead (templates: `UpsertMongoRepository.java`,
`MongoRepositoriesConfig.java`). Its `save()`:
- `id == null` → normal insert.
- `id set, version == null` ("first write") → `replaceOne({_id}, upsert=true)` — replaces a legacy doc
  OR inserts a new client-id doc. One idempotent op covers both ambiguous cases.
- `id set, version present` → version-checked `replaceOne` (`upsert=false`); `matchedCount == 0` →
  `OptimisticLockingFailureException`.

No per-entity change beyond the `@Version` field (the recipe adds it). Wire it once with
`@EnableMongoRepositories(repositoryBaseClass = UpsertMongoRepository.class)`.

**Audit fields on the upsert path — don't miss this.** The template routes everything *except* the
ambiguous `version-null + id-set` case through stock `save()`, where Spring Data auditing fires
normally. Only that one case uses a custom `replace`, which **bypasses auditing** — so the template
sets the audit fields itself there: `@LastModified*` always, `@Created*` only when the document doesn't
already exist (a single `exists()` check). Critically it does this **before** stamping the non-null
version, because auditing/`isNew` consider an entity with a version non-new and would skip `@Created*`.
The audit setters no-op on non-audited entities, so the class is safe for version-only collections too.
(custom-isnew has no such concern — it never bypasses `save()`, so auditing always fires.)

## Gotchas (true on every strategy)

- **New entity with id + a non-null version (doc doesn't exist) → `OptimisticLockingFailureException`,
  not an insert.** This is the `@Version` contract, not a bug: a non-null version means "an existing
  entity at version N", so save looks for that row, finds nothing, and reports a conflict. New entities
  must carry a **null** version. For migration/replication that must preserve a version, use a direct
  `mongoTemplate.insert(...)`, not `save()`.
- **The first migrating write of a legacy doc is not optimistically locked** (there's no prior version
  to check). Two writers racing the same not-yet-versioned doc can both win once; concurrent creates of
  the same client id can raise `DuplicateKeyException` (retry). Locking holds from the next write on.
- **`_id` type consistency.** Spring Data stores a 24-hex `@Id String` as an `ObjectId`. Raw driver
  inserts/queries must use `ObjectId` too, or `findById(hex)` won't find them.
- **New failure mode.** `OptimisticLockingFailureException` didn't exist under last-write-wins. Add
  retry/handling at load-modify-save call sites (see `breakage-catalog.md` V3).

## Maintaining the version on writes
Spring Data 5.x already auto-increments `@Version` on `updateFirst`/`updateMulti`/`upsert`/
`findAndModify` and pipeline updates — no interceptor needed. The only gaps are `bulkOps` (add
`.inc("version",1)`) and `findAndReplace` (drops the version). See `write-paths.md` and add the
`VersionGuardAspect` (template) to WARN on those two.
