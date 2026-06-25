package com.example.versionsandbox.config;

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
 * Custom {@code MongoRepository} base class implementing the <strong>{@code upsert-all-cases}</strong>
 * version-migration strategy: add {@code @Version} to existing collections, going forward only, with
 * <strong>no bulk back-fill</strong> of existing data.
 *
 * <p>Wired in via {@code @EnableMongoRepositories(repositoryBaseClass = UpsertingMongoRepository.class)}
 * (see {@link MongoConfig}). Every {@code MongoRepository} in the app gets this {@code save()}.
 *
 * <h2>Why this and not stock {@code @Version}?</h2>
 * Adding {@code @Version} naively breaks two cases against existing data:
 * <ol>
 *   <li><b>Legacy doc</b> (no {@code version} field): it loads as {@code version == null}; Spring Data's
 *       default {@code isNew()} then treats it as new and tries to INSERT &rarr; the {@code _id} already
 *       exists &rarr; {@code DuplicateKeyException}.</li>
 *   <li><b>Client-assigned id on a brand-new doc</b>: the id is set before {@code save()} but the
 *       document does not exist yet. A {@code Persistable}/{@code custom-isnew} strategy mis-classifies
 *       it as an update &rarr; {@code OptimisticLockingFailureException}.</li>
 * </ol>
 *
 * <p>This base class handles every save shape:
 * <ul>
 *   <li><b>Non-versioned entity, OR a versioned entity that already carries a version</b> &rarr; the
 *       stock {@link SimpleMongoRepository#save(Object)} routing is correct (insert when new, a
 *       version-checked update — real optimistic locking — otherwise). Auditing fires there.</li>
 *   <li><b>version is null AND id is null</b> (a brand-new doc with a server-assigned id) &rarr; the
 *       stock {@code save()} inserts it and stamps {@code version = 0}.</li>
 *   <li><b>version is null AND id is non-null</b> (legacy first-write, OR a brand-new doc with a
 *       client-assigned id) &rarr; stamp {@code version = 0}, apply auditing, and
 *       {@code replace({_id}, doc, upsert=true)}. One round-trip that migrates a legacy doc in place
 *       (its absent {@code version} field matches) AND inserts a brand-new preset-id doc, with a single
 *       existence pre-check used only to decide whether the {@code @Created*} fields should be set.</li>
 * </ul>
 *
 * <h2>Auditing on the upsert path</h2>
 * Spring Data's auditing only sets {@code @Created*} when the entity is <em>new</em>, and an entity
 * stops looking new the moment we stamp a non-null version. So on this path we apply auditing
 * explicitly (mirroring {@code AuditorAware}/{@link CurrentUser}): {@code @LastModified*} on every
 * write, and {@code @Created*} only when the document does not already exist.
 *
 * <h2>Caveats (by design)</h2>
 * <ul>
 *   <li>The first migrating write of a legacy doc has no prior version to check, so two writers racing
 *       a not-yet-migrated doc can both win once; optimistic locking holds from the next write on.</li>
 *   <li>Two concurrent creates of the same client-assigned id can collide; surface/retry the resulting
 *       {@code DuplicateKeyException} at the call site.</li>
 * </ul>
 */
public class UpsertingMongoRepository<T, ID> extends SimpleMongoRepository<T, ID> {

    private final MongoEntityInformation<T, ID> entityInformation;
    private final MongoOperations mongoOperations;

    public UpsertingMongoRepository(MongoEntityInformation<T, ID> entityInformation,
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

        applyAuditing(entity, documentExists);
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
        String user = CurrentUser.get();

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

    /** Set the {@code @Version} property to {@code 0L} via the mapping metadata (no entity-type coupling). */
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
