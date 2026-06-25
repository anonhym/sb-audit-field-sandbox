package com.example.rewrite;

import java.util.List;

/**
 * Adds the four Spring Data auditing fields — {@code createdAt}/{@code createdBy} and
 * {@code updatedAt}/{@code updatedBy} — to every {@code @Document} class that is missing any of them,
 * wiring each to its {@code @CreatedDate} / {@code @CreatedBy} / {@code @LastModifiedDate} /
 * {@code @LastModifiedBy} annotation. A field that already exists is left untouched (so an entity that
 * already has, say, {@code createdAt} keeps it).
 */
public class AddAuditFields extends EnsureFieldsRecipe {

    @Override
    public String getDisplayName() {
        return "Add Spring Data MongoDB audit fields to `@Document` classes";
    }

    @Override
    public String getDescription() {
        return "Ensures every `@Document` class declares `createdAt`, `createdBy`, `updatedAt` and "
                + "`updatedBy` with the matching Spring Data auditing annotations, adding getters and "
                + "setters unless Lombok generates them. Fields that already exist are left untouched.";
    }

    @Override
    List<FieldSpec> fields() {
        return List.of(
                new FieldSpec("createdAt", "Instant", "java.time.Instant",
                        "org.springframework.data.annotation.CreatedDate", "CreatedDate"),
                new FieldSpec("createdBy", "String", null,
                        "org.springframework.data.annotation.CreatedBy", "CreatedBy"),
                new FieldSpec("updatedAt", "Instant", "java.time.Instant",
                        "org.springframework.data.annotation.LastModifiedDate", "LastModifiedDate"),
                new FieldSpec("updatedBy", "String", null,
                        "org.springframework.data.annotation.LastModifiedBy", "LastModifiedBy"));
    }
}
