// TEMPLATE — copy into your project and adjust the package.
// Use this ONLY for the client-assigned-id strategy (ids set before save()). For server-assigned
// ObjectIds, prefer the simpler Persistable/isNew() approach (see references/version-strategy.md) and
// do NOT add this class.
package com.yourorg.config;

import java.time.Instant;

import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.ReplaceOptions;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.SimpleMongoRepository;
import org.springframework.lang.NonNull;

/**
 * Custom {@code MongoRepository} base class implementing the <strong>upsert-all-cases</strong>
 * version-migration strategy: add {@code @Version} to existing collections, going forward only, with
 * <strong>no bulk back-fill</strong>. Wire it with
 * {@code @EnableMongoRepositories(repositoryBaseClass = UpsertMongoRepository.class)} (see
 * {@code MongoRepositoriesConfig}); every {@code MongoRepository} then gets this {@code save()}.
 *
 * <h2>Why this and not stock {@code @Version}?</h2>
 * Naive {@code @Version} breaks two cases against existing data:
 * <ol>
 *   <li><b>Legacy doc</b> (no {@code version} field): loads as {@code version == null}; default
 *       {@code isNew()} treats it as new and tries to INSERT &rarr; the {@code _id} already exists
 *       &rarr; {@code DuplicateKeyException}.</li>
 *   <li><b>Client-assigned id on a brand-new doc</b>: id is set before {@code save()} but the document
 *       doesn't exist yet. A {@code Persistable}/custom-isnew strategy mis-classifies it as an update
 *       &rarr; {@code OptimisticLockingFailureException}. (This is the case custom-isnew can't cover.)</li>
 * </ol>
 *
 * <h2>Routing — every save shape</h2>
 * <ul>
 *   <li><b>Non-versioned entity, OR a versioned entity that already carries a version</b> &rarr; stock
 *       {@link SimpleMongoRepository#save(Object)} is correct (insert when new, a version-checked update
 *       — real optimistic locking — otherwise). <em>Auditing fires on this path.</em></li>
 *   <li><b>version null AND id null</b> (brand-new doc, server-assigned id) &rarr; stock {@code save()}
 *       inserts it and stamps {@code version = 0}.</li>
 *   <li><b>version null AND id non-null</b> (legacy first-write, OR brand-new doc with a client id)
 *       &rarr; stamp {@code version = 0}, apply auditing, {@code replace({_id}, upsert=true)}. One
 *       round-trip migrates a legacy doc in place (its absent {@code version} matches) AND inserts a
 *       preset-id doc.</li>
 * </ul>
 *
 * <h2>Auditing on the upsert path (important)</h2>
 * The custom {@code replace} bypasses Spring Data's normal save-path auditing, and auditing only sets
 * {@code @Created*} while the entity looks <em>new</em> — which it stops doing the moment we stamp a
 * non-null version. So on this path we set the audit fields explicitly, <strong>before</strong> stamping
 * the version: {@code @LastModified*} on every write, and {@code @Created*} only when the document does
 * not already exist (one {@code exists()} pre-check). The audit setters no-op on non-audited entities,
 * so this class is safe to use for version-only collections too.
 *
 * <h2>Caveats (by design)</h2>
 * <ul>
 *   <li>The first migrating write of a legacy doc has no prior version to check, so two writers racing
 *       a not-yet-migrated doc can both win once; locking holds from the next write on.</li>
 *   <li>Two concurrent creates of the same client id can collide; surface/retry the resulting
 *       {@code DuplicateKeyException} at the call site.</li>
 * </ul>
 */
public class UpsertMongoRepository<T, ID> extends SimpleMongoRepository<T, ID> {

    private final MongoEntityInformation<T, ID> entityInformation;
    private final MongoOperations mongoOperations;

    public UpsertMongoRepository(MongoEntityInformation<T, ID> entityInformation,
                                 MongoOperations mongoOperations) {
        super(entityInformation, mongoOperations);
        this.entityInformation = entityInformation;
        this.mongoOperations = mongoOperations;
    }

    @Override
    @NonNull
    public <S extends T> S save(@NonNull S entity) {
        // Stock routing is correct when there is no version, or a version is already present.
        if (!entityInformation.isVersioned() || entityInformation.getVersion(entity) != null) {
            return super.save(entity);
        }

        // version == null below: a legacy first-write, or a brand-new doc.
        Object id = entityInformation.getId(entity);
        if (id == null) {
            // Brand-new doc with a server-assigned id: stock save() inserts it (version becomes 0).
            return super.save(entity);
        }

        // version == null AND id != null: migrate a legacy doc in place, or insert a preset-id doc.
        String collection = entityInformation.getCollectionName();
        boolean documentExists = mongoOperations.exists(
                Query.query(Criteria.where("_id").is(id)), entity.getClass(), collection);

        applyAuditing(entity, documentExists); // BEFORE stamping the version — see class javadoc
        stampInitialVersion(entity);

        mongoOperations.replace(
                Query.query(Criteria.where("_id").is(id)),
                entity,
                ReplaceOptions.replaceOptions().upsert(),
                collection);
        return entity;
    }

    /** Set {@code updatedAt/updatedBy} (always) and {@code createdAt/createdBy} (only when inserting). */
    private <S extends T> void applyAuditing(S entity, boolean documentExists) {
        MongoPersistentEntity<?> persistentEntity = persistentEntity(entity);
        PersistentPropertyAccessor<S> accessor = persistentEntity.getPropertyAccessor(entity);
        Instant now = Instant.now();
        String user = CurrentUser.get(); // same source as your AuditorAware / the AuditFieldsAspect

        setAuditProperty(persistentEntity, accessor,
                org.springframework.data.annotation.LastModifiedDate.class, now);
        setAuditProperty(persistentEntity, accessor,
                org.springframework.data.annotation.LastModifiedBy.class, user);
        if (!documentExists) {
            setAuditProperty(persistentEntity, accessor,
                    org.springframework.data.annotation.CreatedDate.class, now);
            setAuditProperty(persistentEntity, accessor,
                    org.springframework.data.annotation.CreatedBy.class, user);
        }
    }

    private static void setAuditProperty(PersistentEntity<?, ?> persistentEntity,
                                         PersistentPropertyAccessor<?> accessor,
                                         Class<? extends java.lang.annotation.Annotation> annotation,
                                         Object value) {
        for (PersistentProperty<?> property : persistentEntity) {
            if (property.isAnnotationPresent(annotation)) {
                accessor.setProperty(property, value);
                return;
            }
        }
    }

    /** Set the {@code @Version} property to {@code 0L} via mapping metadata (no entity-type coupling). */
    private <S extends T> void stampInitialVersion(S entity) {
        MongoPersistentEntity<?> persistentEntity = persistentEntity(entity);
        PersistentPropertyAccessor<S> accessor = persistentEntity.getPropertyAccessor(entity);
        accessor.setProperty(persistentEntity.getRequiredVersionProperty(), 0L);
    }

    private MongoPersistentEntity<?> persistentEntity(Object entity) {
        return mongoOperations.getConverter().getMappingContext()
                .getRequiredPersistentEntity(entity.getClass());
    }
}
