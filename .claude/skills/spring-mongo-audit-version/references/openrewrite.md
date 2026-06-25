# OpenRewrite — the mechanical field edits

The bundled recipe module (`assets/rewrite-recipes/`) adds the five fields (annotations + imports +
accessors) to every `@Document` class that's missing them. This is the part that scales with the number
of entities, so it's automated; everything else (strategy wiring, infra files, tests) is one-time and
done from templates.

## What the recipe does — and does NOT do

**Does:** for each `@Document` class, add the missing fields after the last existing field, in natural
order, with the right annotation, the needed imports, and a getter/setter (skipped when the class has a
Lombok `@Data`/`@Getter`/`@Setter`/`@Value` annotation). It's idempotent and leaves existing fields
untouched — running it on an already-migrated class is a no-op; a class that already has `createdAt`
keeps it and only gains the others.

**Does NOT:** choose/wire the version migration strategy (`Persistable`/`isNew()` or the upsert repo),
add `@EnableMongoAuditing`, add the aspect/ArchUnit/tests, or add Maven dependencies. Those are
deliberate, project-shaped decisions handled by the other steps. In particular it does **not** make the
entity implement `Persistable` — apply that by hand from `version-strategy.md` for the custom-isnew
strategy.

## Recipes available

| Recipe name | Adds |
|-------------|------|
| `com.example.rewrite.AddMongoAuditAndVersionFields` | `@Version` + the four audit fields |
| `com.example.rewrite.AddMongoAuditFieldsOnly` | the four audit fields only |
| `com.example.rewrite.AddMongoVersionFieldOnly` | `@Version` only |

(The underlying imperative recipes are `AddVersionField` and `AddAuditFields`.)

## Run it (Maven) — two ways

The recipe module is plain Java + OpenRewrite; build it once, then run it against the target.

**1. Build & install the recipe module** (from the skill directory):
```bash
mvn -f assets/rewrite-recipes/pom.xml clean install
# installs com.example.rewrite:mongo-audit-version-recipes:1.0.0 into the local ~/.m2
```

**2a. Run without editing the target's POM** (quickest — uses recipeArtifactCoordinates):
```bash
# from the target project root
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.42.0:run \
    -Drewrite.activeRecipes=com.example.rewrite.AddMongoAuditAndVersionFields \
    -Drewrite.recipeArtifactCoordinates=com.example.rewrite:mongo-audit-version-recipes:1.0.0
```
Use `:dryRun` instead of `:run` first to inspect `target/rewrite/rewrite.patch` without changing files.

**2b. Or wire it into the target POM** (if it'll be run repeatedly / in CI):
```xml
<plugin>
  <groupId>org.openrewrite.maven</groupId>
  <artifactId>rewrite-maven-plugin</artifactId>
  <version>6.42.0</version>
  <configuration>
    <activeRecipes>
      <recipe>com.example.rewrite.AddMongoAuditAndVersionFields</recipe>
    </activeRecipes>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>com.example.rewrite</groupId>
      <artifactId>mongo-audit-version-recipes</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</plugin>
```
then `mvn rewrite:run`. **Remove the plugin block afterward** unless you want it to stay — this is a
one-shot migration, not a permanent build step.

## After running

1. Read the diff. The new fields land after the entity's own fields; imports are added; accessors are
   appended. Field/import formatting follows the project's detected style.
2. `mvn -q compile` to confirm it's valid (verified: the generated code compiles).
3. Do the non-mechanical steps: the version strategy (for custom-isnew, the `Persistable` edit the
   recipe didn't do), the auditing config + aspect, the ArchUnit + behavioural tests.

## Notes / gotchas (learned building this)

- **JDK:** OpenRewrite 8.85 / plugin 6.42.0 runs fine on JDK 17–25 (it auto-selects the matching Java
  parser). The recipe module targets Java 17 bytecode so it builds anywhere ≥17.
- **The recipe inserts un-attributed type nodes on purpose** — it does not put Spring Data on the
  template parser's classpath, because at plugin runtime that classpath isn't the target's. The emitted
  *source text* is correct and re-attributes when the file is parsed for real; the result compiles. (The
  recipe's own unit tests disable after-type-validation for this reason — see the test sources.)
- **Gradle / locked-down builds:** if you can't run the plugin, make the same edits by hand — the recipe
  defines exactly what "done" looks like (fields, annotations, imports, accessors; skip existing; skip
  accessors under Lombok). Don't block the refactor on the tool.
- **Version pinning:** plugin `6.42.0`, `rewrite-recipe-bom 3.33.0` (→ rewrite-core/java `8.85.5`).
  Newer aligned versions are fine; bump the module's BOM + the plugin coordinate together.
