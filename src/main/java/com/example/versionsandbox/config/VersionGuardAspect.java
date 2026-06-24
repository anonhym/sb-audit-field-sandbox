package com.example.versionsandbox.config;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

/**
 * Flags the two {@code MongoTemplate} write paths that do <strong>not</strong> maintain
 * {@code @Version} for a versioned entity.
 *
 * <p>Spring Data MongoDB 5.x already auto-increments the version on {@code updateFirst},
 * {@code updateMulti}, {@code upsert}, {@code findAndModify}, and pipeline
 * {@code AggregationUpdate} writes — so no interception is needed there. The exceptions, verified
 * live against this project, are:
 *
 * <ul>
 *   <li><b>{@code bulkOps}</b> — bulk updates are <em>not</em> version-incremented; the field is
 *       left untouched.</li>
 *   <li><b>{@code findAndReplace}</b> — replaces the whole document and <em>removes</em> the version
 *       field unless the replacement object carries it, silently de-versioning the document.</li>
 * </ul>
 *
 * <p>Policy: log a WARN and let the call proceed. The guard is generic — it fires for any entity that
 * has an {@code @Version} property (discovered via the mapping context), not just {@code Product}.
 */
@Aspect
@Component
public class VersionGuardAspect {

    private static final Logger log = LoggerFactory.getLogger(VersionGuardAspect.class);

    private final MongoMappingContext mappingContext;

    public VersionGuardAspect(MongoMappingContext mappingContext) {
        this.mappingContext = mappingContext;
    }

    @Before("execution(* org.springframework.data.mongodb.core.MongoOperations.bulkOps(..))")
    public void guardBulkOps(JoinPoint jp) {
        MongoPersistentEntity<?> entity = versionedEntityFromArgs(jp.getArgs());
        if (entity != null) {
            log.warn("[version-guard] bulkOps on {} does NOT auto-increment @Version (unlike "
                            + "updateFirst/updateMulti/upsert/findAndModify). Add .inc(\"{}\", 1) to each bulk update.",
                    entity.getType().getSimpleName(), entity.getVersionProperty().getName());
        }
    }

    @Before("execution(* org.springframework.data.mongodb.core.MongoOperations.findAndReplace(..))")
    public void guardFindAndReplace(JoinPoint jp) {
        MongoPersistentEntity<?> entity = versionedEntityFromArgs(jp.getArgs());
        if (entity != null) {
            log.warn("[version-guard] findAndReplace on {} does NOT maintain @Version and will DROP the "
                            + "'{}' field unless the replacement carries it. Prefer save()/findAndModify, or set the "
                            + "current version on the replacement.",
                    entity.getType().getSimpleName(), entity.getVersionProperty().getName());
        }
    }

    /**
     * Resolves the versioned entity for a call from either an explicit {@code Class} argument
     * (e.g. {@code bulkOps(mode, Class)}) or a domain-object argument (e.g. the {@code replacement}
     * in {@code findAndReplace(query, replacement, options)}).
     */
    private MongoPersistentEntity<?> versionedEntityFromArgs(Object[] args) {
        MongoPersistentEntity<?> byClass = versionedEntity(firstOfType(args, Class.class));
        if (byClass != null) {
            return byClass;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            MongoPersistentEntity<?> byInstance = versionedEntity(arg.getClass());
            if (byInstance != null) {
                return byInstance;
            }
        }
        return null;
    }

    private MongoPersistentEntity<?> versionedEntity(Class<?> type) {
        if (type == null) {
            return null;
        }
        try {
            MongoPersistentEntity<?> entity = mappingContext.getPersistentEntity(type);
            return (entity != null && entity.hasVersionProperty()) ? entity : null;
        } catch (RuntimeException ex) {
            return null; // not a mappable type (Query, options, BulkMode, ...) — ignore
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
