package com.example.versionsandbox.config;

import java.time.Instant;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

/**
 * Fills the audit fields on the lower-level {@code MongoTemplate} write paths, which Spring Data
 * auditing does <strong>not</strong> cover (auditing only fires on the {@code save()} /
 * entity-conversion path). Without this, the documented failure modes appear: {@code updatedBy} goes
 * stale on every template update, and a template {@code upsert}-insert leaves {@code createdBy} /
 * {@code updatedBy} null.
 *
 * <p>For each {@code updateFirst} / {@code updateMulti} / {@code upsert} / {@code findAndModify} on an
 * <em>audited</em> entity type (see {@link AuditFields#isAudited(Class)}), this aspect enriches the
 * {@link Update} in place, <strong>adding only what's missing</strong>:
 * <ul>
 *   <li>{@code updatedAt} / {@code updatedBy} via {@code $set} — refreshed every write (skipped only if
 *       the caller already set them, so an explicit value wins);</li>
 *   <li>{@code createdAt} / {@code createdBy} via {@code $setOnInsert} — applied only on the insert
 *       branch of an upsert, and only if the caller didn't already set them.</li>
 * </ul>
 *
 * <p>The current user comes from {@link CurrentUser} (the same source as {@code AuditorAware}), so the
 * template paths and the {@code save()} path attribute writes to the same auditor.
 *
 * <h2>What this aspect deliberately does NOT touch</h2>
 * {@code bulkOps} and {@code findAndReplace} cannot be safely corrected from here ({@code bulkOps}
 * builds its updates behind a builder with no single {@code Update} argument to enrich;
 * {@code findAndReplace} replaces the whole document and resets {@code createdAt}/{@code createdBy}).
 * Those are handled structurally: the ArchUnit rule bans them on audited entities. See
 * {@code PersistenceFieldsRulesTest}.
 */
@Aspect
@Component
public class AuditTemplateAspect {

    @Before("execution(* org.springframework.data.mongodb.core.MongoOperations.updateFirst(..)) "
            + "|| execution(* org.springframework.data.mongodb.core.MongoOperations.updateMulti(..)) "
            + "|| execution(* org.springframework.data.mongodb.core.MongoOperations.upsert(..)) "
            + "|| execution(* org.springframework.data.mongodb.core.MongoOperations.findAndModify(..))")
    public void enrichAuditFields(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        Update update = firstUpdate(args);
        Class<?> entityType = entityClassArg(args);
        if (update == null || entityType == null || !AuditFields.isAudited(entityType)) {
            return;
        }

        Instant now = Instant.now();
        String user = CurrentUser.get();

        setIfAbsent(update, AuditFields.updatedAt(entityType), now);
        setIfAbsent(update, AuditFields.updatedBy(entityType), user);
        setOnInsertIfAbsent(update, AuditFields.createdAt(entityType), now);
        setOnInsertIfAbsent(update, AuditFields.createdBy(entityType), user);
    }

    private static void setIfAbsent(Update update, String field, Object value) {
        if (field != null && !update.modifies(field)) {
            update.set(field, value);
        }
    }

    private static void setOnInsertIfAbsent(Update update, String field, Object value) {
        // modifies(field) is true if the caller already set OR setOnInsert'd the field; don't double up.
        if (field != null && !update.modifies(field)) {
            update.setOnInsert(field, value);
        }
    }

    /** The {@code Update} argument (every intercepted method takes exactly one {@link UpdateDefinition}). */
    private static Update firstUpdate(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Update update) {
                return update;
            }
            if (arg instanceof UpdateDefinition) {
                // A non-Update UpdateDefinition (e.g. AggregationUpdate / pipeline) — Spring Data 5.x
                // already maintains @Version on those, and we can't append $set to a pipeline safely.
                return null;
            }
        }
        return null;
    }

    /** The entity {@code Class<?>} argument that tells the template which collection/type is targeted. */
    private static Class<?> entityClassArg(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Class<?> type) {
                return type;
            }
        }
        return null;
    }
}
