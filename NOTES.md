# approach/explicit-upsert

**Mechanism:** `@Version Long` + a custom repository base class
(`config/UpsertMongoRepository`, wired by `config/MongoRepositoriesConfig` via
`@EnableMongoRepositories(repositoryBaseClass = …)`). It overrides `save()`:

- **id == null** → normal insert (`@Version` sets `version = 0`).
- **id present** → explicit `replaceOne({_id}, doc, upsert=false)`, keyed on the id, not the version.
  A legacy doc with no version field is replaced and stamped with `version = 0` — never mis-routed to
  an insert, so no duplicate key and no back-fill.

Optimistic locking is hand-rolled: when the loaded version is non-null, the replace also filters on
`version = <loaded>`; a concurrent change yields `matchedCount == 0` → `OptimisticLockingFailureException`.

**Trade-off to confirm:** the first migrating write of a legacy doc has no prior version to check, so
two writers racing on a not-yet-migrated document can both win that first time. Locking applies from
the next write onward.

**Run it:**
```
./mvnw spring-boot:run
POST /api/experiments/legacy-doc?name=Legacy%20Widget   -> id
POST /api/experiments/{id}/load-then-save               -> expected: SAVED, version=0 stamped
POST /api/experiments/{id}/load-then-save               -> again: version -> 1
POST /api/experiments/{id}/concurrent-update             -> 2nd writer locks (after migration)
```

**Observed:** _(filled in from the live run — see APPROACHES.md matrix on `main`)_
