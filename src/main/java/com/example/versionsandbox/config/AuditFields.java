package com.example.versionsandbox.config;

import java.lang.reflect.Field;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Single source of truth for the four audit field <em>document</em> names and for deciding whether a
 * given entity type is audited. Shared by {@link AuditTemplateAspect} (which fills the fields on the
 * {@code MongoTemplate} write paths) and, conceptually, by the ArchUnit rule (which enforces that the
 * fields are declared). Field names are resolved from the {@code @CreatedDate} / {@code @CreatedBy} /
 * {@code @LastModifiedDate} / {@code @LastModifiedBy} annotations so the two stay in lock-step even if
 * a field is renamed.
 */
public final class AuditFields {

    private AuditFields() {
    }

    /** True when {@code type} declares all four audit fields (with the Spring Data audit annotations). */
    public static boolean isAudited(Class<?> type) {
        return createdDateField(type) != null
                && fieldAnnotatedWith(type, CreatedBy.class) != null
                && fieldAnnotatedWith(type, LastModifiedDate.class) != null
                && fieldAnnotatedWith(type, LastModifiedBy.class) != null;
    }

    public static String createdAt(Class<?> type) {
        return nameOf(createdDateField(type));
    }

    public static String createdBy(Class<?> type) {
        return nameOf(fieldAnnotatedWith(type, CreatedBy.class));
    }

    public static String updatedAt(Class<?> type) {
        return nameOf(fieldAnnotatedWith(type, LastModifiedDate.class));
    }

    public static String updatedBy(Class<?> type) {
        return nameOf(fieldAnnotatedWith(type, LastModifiedBy.class));
    }

    private static Field createdDateField(Class<?> type) {
        return fieldAnnotatedWith(type, CreatedDate.class);
    }

    private static Field fieldAnnotatedWith(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(annotation)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String nameOf(Field f) {
        return f == null ? null : f.getName();
    }
}
