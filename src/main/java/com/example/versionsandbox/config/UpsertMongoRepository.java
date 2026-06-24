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
 * approach/upsert-all-cases — handles every save shape, including a brand-new document whose id is
 * set <em>before</em> saving (client-assigned id).
 *
 * <p>A drop-in {@link SimpleMongoRepository} replacement (wired via
 * {@code @EnableMongoRepositories(repositoryBaseClass = …)}) that overrides {@code save()} so the
 * harness's existing {@code repository.save(...)} calls don't have to change. The decision is keyed
 * on the <strong>id</strong> and the version:
 * <ul>
 *   <li><b>id == null</b> → brand-new entity → normal insert ({@code @Version} initialised to 0).</li>
 *   <li><b>id set, version == null</b> ("first write") → {@code replaceOne} by {@code _id} with
 *       {@code upsert = true}. That single idempotent operation covers <em>both</em> ambiguous cases:
 *       a pre-existing legacy document is replaced (and stamped with {@code version = 0}), and a
 *       genuinely new document with a client-assigned id is inserted. No duplicate key, no spurious
 *       optimistic-lock failure, no back-fill.</li>
 *   <li><b>id set, version present</b> → version-checked {@code replaceOne} ({@code upsert = false},
 *       filter on {@code version}); a concurrent change yields {@code matchedCount == 0} and we raise
 *       {@link OptimisticLockingFailureException}.</li>
 * </ul>
 *
 * <p>This is the only branch that covers the client-set-id-on-a-new-document case. {@code custom-isnew}
 * and the original {@code explicit-upsert} both mis-classify it as an update and fail. <strong>Trade-off
 * (unchanged):</strong> the first write of a not-yet-versioned document has no prior version to check,
 * so two writers racing on it can both win that once; locking applies from the next write onward.
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
        Object loadedVersion = doc.get("version"); // null for a legacy or a brand-new client-id document
        boolean firstWrite = (loadedVersion == null);
        long nextVersion = firstWrite ? 0L : ((Number) loadedVersion).longValue() + 1L;
        doc.put("version", nextVersion);

        // First write: key on _id and upsert -> replaces a legacy doc OR inserts a new client-id doc.
        // Subsequent writes: also filter on the loaded version to enforce optimistic locking.
        Bson filter = firstWrite
                ? Filters.eq("_id", id)
                : Filters.and(Filters.eq("_id", id), Filters.eq("version", loadedVersion));

        UpdateResult result = mongoOperations.getCollection(entityInformation.getCollectionName())
                .replaceOne(filter, doc, new ReplaceOptions().upsert(firstWrite));

        if (!firstWrite && result.getMatchedCount() == 0) {
            throw new OptimisticLockingFailureException(
                    "Cannot save " + entityInformation.getJavaType().getSimpleName() + " " + id
                            + " with version " + loadedVersion + "; it was modified or removed concurrently");
        }
        return entity;
    }
}
