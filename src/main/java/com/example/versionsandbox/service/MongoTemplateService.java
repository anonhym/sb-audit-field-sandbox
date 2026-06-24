package com.example.versionsandbox.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.versionsandbox.domain.Product;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Everything that goes through the lower-level {@code MongoTemplate} API rather than the
 * repository: ad-hoc {@link Criteria} queries, partial {@code updateMulti} updates,
 * aggregations, and the two things that make the version sandbox interesting —
 * <em>writing a document with no {@code version} field</em> and
 * <em>back-filling that field across the whole collection</em>.
 */
@Service
public class MongoTemplateService {

    static final String COLLECTION = "products";

    private final MongoTemplate mongoTemplate;

    public MongoTemplateService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ----------------------------------------------------------------------
    // Plain reads/queries via Criteria
    // ----------------------------------------------------------------------

    public List<Product> findByCategoryAndMinPrice(String category, BigDecimal minPrice) {
        Criteria criteria = new Criteria();
        if (category != null) {
            criteria.and("category").is(category);
        }
        if (minPrice != null) {
            criteria.and("price").gte(minPrice);
        }
        return mongoTemplate.find(new Query(criteria), Product.class);
    }

    /** Read the document exactly as stored, so you can SEE whether a {@code version} field exists. */
    public Document findRawById(String id) {
        Document doc = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", new org.bson.types.ObjectId(id)))
                .first();
        return readableId(doc);
    }

    public List<Document> findAllRaw() {
        List<Document> docs = mongoTemplate.getCollection(COLLECTION)
                .find()
                .into(new java.util.ArrayList<>());
        docs.forEach(MongoTemplateService::readableId);
        return docs;
    }

    /**
     * The {@code version} field exactly as stored, read straight from BSON (not from the mapped
     * entity). The shared harness reports version through this method so it compiles and runs the
     * same on the no-version baseline (returns {@code null}) and on every {@code approach/*} branch.
     */
    public Object storedVersion(String id) {
        Document doc = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", new org.bson.types.ObjectId(id)))
                .first();
        return doc == null ? null : doc.get("version");
    }

    /** Render an {@code ObjectId} {@code _id} as its hex string so JSON output stays readable. */
    private static Document readableId(Document doc) {
        if (doc != null && doc.get("_id") instanceof org.bson.types.ObjectId oid) {
            doc.put("_id", oid.toHexString());
        }
        return doc;
    }

    // ----------------------------------------------------------------------
    // Partial update (no version check) — the "I just want to bump a field" path
    // ----------------------------------------------------------------------

    /**
     * Increases stock with a raw {@code $inc}. Note this bypasses optimistic locking entirely:
     * {@code MongoTemplate.updateFirst} does NOT touch the {@code @Version} field, so concurrent
     * callers here will happily clobber each other. Contrast with the repository {@code save()} path.
     */
    public long incrementStock(String id, int delta) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().inc("stock", delta).set("updatedAt", Instant.now()),
                Product.class).getModifiedCount();
    }

    public long raisePricesInCategory(String category, BigDecimal multiplier) {
        return mongoTemplate.updateMulti(
                Query.query(Criteria.where("category").is(category)),
                new Update().multiply("price", multiplier).set("updatedAt", Instant.now()),
                Product.class).getModifiedCount();
    }

    // ----------------------------------------------------------------------
    // Aggregation — group counts by category and by version value
    // ----------------------------------------------------------------------

    public List<Document> countByCategory() {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.group("category").count().as("count"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count"));
        AggregationResults<Document> results = mongoTemplate.aggregate(agg, COLLECTION, Document.class);
        return results.getMappedResults();
    }

    // ----------------------------------------------------------------------
    // The version migration machinery
    // ----------------------------------------------------------------------

    /**
     * Inserts a "legacy" document <strong>without</strong> a {@code version} field, by writing a
     * raw {@link Document} straight to the collection and bypassing entity mapping. This is what a
     * document looks like if it was created before {@code @Version} was added to the model.
     *
     * @return the generated id
     */
    public String insertLegacyDocumentWithoutVersion(String name, String category, BigDecimal price, int stock) {
        // Use a real ObjectId for _id so the document is keyed exactly like the ones Spring Data
        // writes (it stores 24-char-hex @Id strings as ObjectId). If we stored _id as a String,
        // repository.findById(hex) would query by ObjectId and silently miss it.
        org.bson.types.ObjectId oid = new org.bson.types.ObjectId();
        Document legacy = new Document("_id", oid)
                .append("name", name)
                .append("category", category)
                .append("price", price)
                .append("stock", stock)
                .append("createdAt", Instant.now())
                // deliberately NO "version" field
                .append("_class", Product.class.getName());
        mongoTemplate.getCollection(COLLECTION).insertOne(legacy);
        return oid.toHexString();
    }

    /** How many documents are missing the version field, and the distribution of version values. */
    public Map<String, Object> versionStats() {
        long missing = mongoTemplate.getCollection(COLLECTION)
                .countDocuments(new Document("version", new Document("$exists", false)));
        long total = mongoTemplate.getCollection(COLLECTION).countDocuments();

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.group("version").count().as("count"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id"));
        List<Document> byVersion = mongoTemplate.aggregate(agg, COLLECTION, Document.class).getMappedResults();

        return Map.of(
                "total", total,
                "missingVersionField", missing,
                "byVersionValue", byVersion);
    }

    public void dropCollection() {
        mongoTemplate.dropCollection(COLLECTION);
    }
}
