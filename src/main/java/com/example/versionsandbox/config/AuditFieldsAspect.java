package com.example.versionsandbox.config;

import java.time.Instant;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

/**
 * Keeps the four audit fields correct on the {@code MongoTemplate} write paths that Spring Data
 * auditing does <strong>not</strong> cover (auditing only fires on the {@code save()} /
 * entity-conversion path).
 *
 * <p>For {@code updateFirst}, {@code updateMulti}, {@code upsert} and {@code findAndModify} on an
 * audited entity it injects, <em>only what the caller didn't already set</em>:
 * <ul>
 *   <li>{@code updatedAt} / {@code updatedBy} via {@code $set} (every write), and</li>
 *   <li>{@code createdAt} / {@code createdBy} via {@code $setOnInsert} (insert branch only).</li>
 * </ul>
 *
 * <p>{@code findAndReplace} (whole-document swap, which resets the created fields) and {@code bulkOps}
 * (updates registered on a separate builder) cannot be fixed from a simple aspect — they are logged at
 * WARN. Pair this aspect with the ArchUnit rule that bans those two operations on audited entities.
 *
 * <p>"Audited" is detected generically from the mapping context (the entity has {@code updatedAt} +
 * {@code updatedBy} properties), so this fires for every such entity, not a hard-coded type.
 */
@Aspect
@Component
public class AuditFieldsAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditFieldsAspect.class);

    private final MongoMappingContext mappingContext;

    public AuditFieldsAspect(MongoMappingContext mappingContext) {
        this.mappingContext = mappingContext;
    }

    @Around("execution(* org.springframework.data.mongodb.core.MongoOperations.updateFirst(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations.updateMulti(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations.upsert(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations.findAndModify(..))")
    public Object injectAuditFields(ProceedingJoinPoint pjp) throws Throwable {
        UpdateDefinition update = firstOfType(pjp.getArgs(), UpdateDefinition.class);
        Class<?> entityType = firstOfType(pjp.getArgs(), Class.class);

        if (audited(entityType) && update instanceof Update u) {
            Instant now = Instant.now();
            String user = currentUser();
            if (!u.modifies("updatedAt")) {
                u.set("updatedAt", now);
            }
            if (!u.modifies("updatedBy")) {
                u.set("updatedBy", user);
            }
            // $setOnInsert only takes effect when the operation inserts (the upsert insert branch).
            if (!u.modifies("createdAt")) {
                u.setOnInsert("createdAt", now);
            }
            if (!u.modifies("createdBy")) {
                u.setOnInsert("createdBy", user);
            }
        } else if (entityType != null && audited(entityType) && update != null) {
            log.warn("[audit] {}.{} uses a {} (pipeline) update; audit fields not injected",
                    entityType.getSimpleName(), pjp.getSignature().getName(), update.getClass().getSimpleName());
        }
        return pjp.proceed();
    }

    @Before("execution(* org.springframework.data.mongodb.core.MongoOperations.findAndReplace(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations.bulkOps(..))")
    public void warnUncoverable(JoinPoint jp) {
        Class<?> byClass = firstOfType(jp.getArgs(), Class.class);
        boolean audited = audited(byClass);
        for (Object arg : jp.getArgs()) {
            if (!audited && arg != null && audited(arg.getClass())) {
                audited = true;
            }
        }
        if (audited) {
            log.warn("[audit] {} on an audited entity does NOT maintain audit fields and cannot be "
                    + "auto-fixed by this aspect; avoid it on audited types (ArchUnit can ban it).",
                    jp.getSignature().getName());
        }
    }

    /**
     * The auditor for {@code @CreatedBy}/{@code @LastModifiedBy}. Wired to the SAME source the
     * {@code AuditorAware} uses (the request-scoped {@link CurrentUser}, populated from the {@code X-User}
     * header by {@link CurrentUserFilter}) so the {@code save()} path and the template path agree.
     */
    private String currentUser() {
        return CurrentUser.get();
    }

    private boolean audited(Class<?> type) {
        if (type == null) {
            return false;
        }
        try {
            MongoPersistentEntity<?> entity = mappingContext.getPersistentEntity(type);
            return entity != null
                    && entity.getPersistentProperty("updatedAt") != null
                    && entity.getPersistentProperty("updatedBy") != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T firstOfType(Object[] args, Class<T> type) {
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return (T) arg;
            }
        }
        return null;
    }
}
