package com.example.rewrite;

import java.util.List;

/**
 * Adds a nullable {@code @Version Long version} field (Spring Data's optimistic-locking marker) to
 * every {@code @Document} class that does not already have one, with a getter/setter unless Lombok
 * generates them.
 *
 * <p><strong>This only adds the field.</strong> It deliberately does <em>not</em> choose or wire a
 * no-back-fill migration strategy ({@code Persistable}/{@code isNew()} for server-assigned ids, or a
 * custom upsert repository for client-assigned ids) — that decision depends on how ids are assigned
 * per collection and is handled by the skill, not mechanically. Adding {@code @Version} without that
 * strategy will break {@code save()} on existing version-less documents (duplicate key), so run this
 * as one step of the skill's workflow, not on its own.
 */
public class AddVersionField extends EnsureFieldsRecipe {

    @Override
    public String getDisplayName() {
        return "Add `@Version` to `@Document` classes";
    }

    @Override
    public String getDescription() {
        return "Ensures every `@Document` class declares a nullable `Long version` field annotated "
                + "with Spring Data's `@Version` for optimistic locking, adding a getter/setter unless "
                + "Lombok generates them. Classes that already have a version field are left untouched. "
                + "Does not wire a migration strategy (Persistable / custom repository); the skill does "
                + "that separately based on how ids are assigned.";
    }

    @Override
    List<FieldSpec> fields() {
        return List.of(
                new FieldSpec("version", "Long", null,
                        "org.springframework.data.annotation.Version", "Version"));
    }
}
