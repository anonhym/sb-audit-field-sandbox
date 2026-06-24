package com.example.versionsandbox.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.versionsandbox.domain.Product;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.SetOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Everything that goes through the lower-level {@code MongoTemplate} API rather than the repository:
 * ad-hoc {@link Criteria} queries; the full range of writes ({@code updateFirst}, {@code updateMulti},
 * {@code upsert}, {@code findAndModify}, {@code findAndReplace}, {@code bulkOps}, and pipeline
 * {@link AggregationUpdate}); aggregations; and the raw-driver escape hatch used to write a
 * version-less document.
 *
 * <p>Note on ids: queries issued through {@code MongoTemplate} with {@code Product.class} run through
 * Spring Data's {@code QueryMapper}, which converts a 24-char-hex {@code _id} string to an
 * {@code ObjectId} automatically — so these methods take the hex id as a {@code String}. The
 * raw-driver methods ({@code getCollection()...}) do not get that conversion and build the
 * {@code ObjectId} explicitly.
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

    // ----------------------------------------------------------------------
    // The rest of the MongoTemplate update surface
    // ----------------------------------------------------------------------

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }

    /**
     * {@code upsert} — update the match or insert if none. {@code $setOnInsert} fields are only
     * written on the insert branch (here: {@code createdAt}).
     */
    public Map<String, Object> upsertByName(String name, String category, BigDecimal price, int stock) {
        UpdateResult r = mongoTemplate.upsert(
                Query.query(Criteria.where("name").is(name)),
                new Update()
                        .set("category", category)
                        .set("price", price)
                        .set("stock", stock)
                        .set("updatedAt", Instant.now())
                        .setOnInsert("createdAt", Instant.now()),
                Product.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", r.getMatchedCount());
        out.put("modified", r.getModifiedCount());
        out.put("upsertedId", r.getUpsertedId() == null ? null : r.getUpsertedId().asObjectId().getValue().toHexString());
        return out;
    }

    /** {@code findAndModify} — atomic update returning the post-update document ({@code returnNew}). */
    public Product findAndIncrementStock(String id, int delta) {
        return mongoTemplate.findAndModify(
                byId(id),
                new Update().inc("stock", delta).set("updatedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true),
                Product.class);
    }

    /** {@code findAndReplace} — atomically swap the whole document, keeping its {@code _id}. */
    public Product findAndReplaceById(String id, Product replacement) {
        replacement.setId(null); // keep the matched document's _id
        replacement.setUpdatedAt(Instant.now());
        return mongoTemplate.findAndReplace(
                byId(id),
                replacement,
                FindAndReplaceOptions.options().returnNew());
    }

    /**
     * {@code bulkOps} — several writes in one round-trip. Here a batched {@code updateMulti} plus an
     * {@code upsert}, executed together.
     */
    public Map<String, Object> bulkMixedWrite(String category) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.ORDERED, Product.class);
        bulk.updateMulti(
                Query.query(Criteria.where("category").is(category)),
                new Update().inc("stock", 1).set("updatedAt", Instant.now()));
        bulk.upsert(
                Query.query(Criteria.where("name").is("Bulk Upserted Item")),
                new Update()
                        .set("category", category)
                        .set("price", new BigDecimal("1.00"))
                        .set("stock", 0)
                        .setOnInsert("createdAt", Instant.now()));
        BulkWriteResult r = bulk.execute();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", r.getMatchedCount());
        out.put("modified", r.getModifiedCount());
        out.put("inserted", r.getInsertedCount());
        out.put("upserts", r.getUpserts().size());
        return out;
    }

    /**
     * Pipeline update ({@link AggregationUpdate}, Mongo 4.2+) — set a field from a computation over
     * other fields of the same document. Here {@code inventoryValue = price * stock}, server-side,
     * which a plain {@code Update} cannot express.
     */
    public long computeInventoryValue() {
        AggregationUpdate update = AggregationUpdate.update().set(
                SetOperation.set("inventoryValue")
                        .toValueOf(ArithmeticOperators.valueOf("price").multiplyBy("stock")));
        return mongoTemplate.updateMulti(new Query(), update, Product.class).getModifiedCount();
    }

    // --- single-field operators on a (schemaless) "tags" array and elsewhere ---
    // "tags" is not mapped on Product; it is written/read as a raw array to exercise the array
    // operators. Inspect the result with GET /api/products/{id}/raw.

    /** {@code $addToSet} — append to the {@code tags} array only if not already present. */
    public long addTag(String id, String tag) {
        return mongoTemplate.updateFirst(byId(id), new Update().addToSet("tags", tag), Product.class).getModifiedCount();
    }

    /** {@code $push} — append to the {@code tags} array, duplicates allowed. */
    public long pushTag(String id, String tag) {
        return mongoTemplate.updateFirst(byId(id), new Update().push("tags", tag), Product.class).getModifiedCount();
    }

    /** {@code $pull} — remove all matching entries from the {@code tags} array. */
    public long pullTag(String id, String tag) {
        return mongoTemplate.updateFirst(byId(id), new Update().pull("tags", tag), Product.class).getModifiedCount();
    }

    /** {@code $unset} — remove a field entirely. */
    public long unsetField(String id, String field) {
        return mongoTemplate.updateFirst(byId(id), new Update().unset(field), Product.class).getModifiedCount();
    }

    /** {@code $min} — only lower the price (write wins only if smaller than the stored value). */
    public long minPrice(String id, BigDecimal price) {
        return mongoTemplate.updateFirst(byId(id), new Update().min("price", price), Product.class).getModifiedCount();
    }

    /** {@code $currentDate} — let the server stamp {@code updatedAt}. */
    public long touch(String id) {
        return mongoTemplate.updateFirst(byId(id), new Update().currentDate("updatedAt"), Product.class).getModifiedCount();
    }

    public void dropCollection() {
        mongoTemplate.dropCollection(COLLECTION);
    }
}
