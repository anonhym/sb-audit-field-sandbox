// TEMPLATE — copy into your project and adjust the package.
// Use this ONLY for the client-assigned-id strategy (ids set before save()). For server-assigned
// ObjectIds, prefer the simpler Persistable/isNew() approach (see references/version-strategy.md) and
// do NOT add this class.
package com.yourorg.config;

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
 * A drop-in {@link SimpleMongoRepository} replacement that makes {@code save()} work for every shape,
 * including a brand-new document whose id is set <em>before</em> saving (client-assigned id) — the one
 * case {@code Persistable}/{@code isNew()} mis-classifies as an update. Wire it with
 * {@code @EnableMongoRepositories(repositoryBaseClass = UpsertMongoRepository.class)} so existing
 * {@code repository.save(...)} call sites don't change.
 *
 * <p>The decision is keyed on the id and the version:
 * <ul>
 *   <li><b>id == null</b> → brand-new entity → normal insert ({@code @Version} initialised to 0).</li>
 *   <li><b>id set, version == null</b> ("first write") → {@code replaceOne} by {@code _id} with
 *       {@code upsert = true}: replaces a pre-existing legacy document (stamping {@code version = 0})
 *       OR inserts a genuinely new client-id document. No duplicate key, no spurious lock failure, no
 *       back-fill.</li>
 *   <li><b>id set, version present</b> → version-checked {@code replaceOne} (filter on {@code version},
 *       {@code upsert = false}); a concurrent change yields {@code matchedCount == 0} →
 *       {@link OptimisticLockingFailureException}.</li>
 * </ul>
 *
 * <p><strong>Trade-off:</strong> the very first write of a not-yet-versioned document has no prior
 * version to check, so two writers racing it can both win that once; optimistic locking applies from
 * the next write onward.
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
