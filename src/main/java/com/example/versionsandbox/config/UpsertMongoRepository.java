package com.example.versionsandbox.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.SimpleMongoRepository;

/**
 * approach/explicit-upsert.
 *
 * <p>A drop-in {@link SimpleMongoRepository} replacement (wired via
 * {@code @EnableMongoRepositories(repositoryBaseClass = …)}) that overrides {@code save()} so the
 * harness's existing {@code repository.save(...)} calls don't have to change.
 *
 * <p>The decision is keyed on the <strong>id</strong>, not the version:
 * <ul>
 *   <li>id is {@code null} → brand-new entity → normal insert (the {@code @Version} field is
 *       initialised to {@code 0}).</li>
 *   <li>id is present → explicit {@code replaceOne} by {@code _id}. A legacy document with no version
 *       field is therefore replaced (and stamped with {@code version = 0}) instead of being
 *       mis-routed to an insert — no duplicate key, no back-fill.</li>
 * </ul>
 *
 * <p>Optimistic locking is hand-rolled: when the loaded version is non-null the replace also filters
 * on {@code version = <loaded>}, so a concurrent change makes {@code matchedCount == 0} and we raise
 * {@link OptimisticLockingFailureException}. <strong>Trade-off:</strong> the very first migrating
 * write of a legacy doc has no prior version to check, so two writers racing on a not-yet-migrated
 * document can both win that first time; locking applies from the next write onward.
 */
public class UpsertMongoRepository<T, ID> extends SimpleMongoRepository<T, ID> {

    private final MongoEntityInformation<T, ID> entityInformation;
    private final MongoOperations mongoOperations;

    public UpsertMongoRepository(MongoEntityInformation<T, ID> entityInformation, MongoOperations mongoOperations) {
        super(entityInformation, mongoOperations);
        this.entityInformation = entityInformation;
        this.mongoOperations = mongoOperations;
    }

    @Override
    public <S extends T> S save(S entity) {
        if (entityInformation.getId(entity) == null) {
            // No id yet -> genuine insert; @Version initialises version to 0.
            return super.save(entity);
        }

        Document doc = new Document();
        mongoOperations.getConverter().write(entity, doc);

        Object id = doc.get("_id");
        Object loadedVersion = doc.get("version"); // null for a legacy, never-versioned document
        long nextVersion = (loadedVersion == null) ? 0L : ((Number) loadedVersion).longValue() + 1L;
        doc.put("version", nextVersion);

        Bson filter = (loadedVersion == null)
                ? Filters.eq("_id", id)
                : Filters.and(Filters.eq("_id", id), Filters.eq("version", loadedVersion));

        UpdateResult result = mongoOperations.getCollection(entityInformation.getCollectionName())
                .replaceOne(filter, doc, new ReplaceOptions().upsert(false));

        if (result.getMatchedCount() == 0) {
            throw new OptimisticLockingFailureException(
                    "Cannot save " + entityInformation.getJavaType().getSimpleName() + " " + id
                            + " with version " + loadedVersion + "; it was modified or removed concurrently");
        }
        return entity;
    }
}
