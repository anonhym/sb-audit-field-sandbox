package com.example.versionsandbox.web;

import java.math.BigDecimal;
import java.util.Map;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.service.MongoTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercises the rest of the {@code MongoTemplate} update surface beyond the basic
 * {@code updateFirst}/{@code updateMulti} used elsewhere: {@code upsert}, {@code findAndModify},
 * {@code findAndReplace}, {@code bulkOps}, a pipeline {@link org.springframework.data.mongodb.core.aggregation.AggregationUpdate},
 * and the field/array operators ({@code $setOnInsert}, {@code $addToSet}, {@code $push}, {@code $pull},
 * {@code $unset}, {@code $min}, {@code $currentDate}).
 *
 * <p>The {@code tags} array is schemaless (not mapped on {@code Product}); inspect array changes with
 * {@code GET /api/products/{id}/raw}.
 */
@RestController
@RequestMapping("/api/updates")
public class UpdateOpsController {

    private final MongoTemplateService mongoTemplateService;

    public UpdateOpsController(MongoTemplateService mongoTemplateService) {
        this.mongoTemplateService = mongoTemplateService;
    }

    /** upsert with $setOnInsert (update by name, or insert if missing). */
    @PostMapping("/upsert")
    public Map<String, Object> upsert(@RequestParam String name,
                                      @RequestParam(defaultValue = "misc") String category,
                                      @RequestParam(defaultValue = "9.99") BigDecimal price,
                                      @RequestParam(defaultValue = "1") int stock) {
        return mongoTemplateService.upsertByName(name, category, price, stock);
    }

    /** findAndModify: atomic $inc returning the post-update document. */
    @PostMapping("/{id}/find-and-modify")
    public ResponseEntity<Product> findAndModify(@PathVariable String id,
                                                 @RequestParam(defaultValue = "1") int delta) {
        Product updated = mongoTemplateService.findAndIncrementStock(id, delta);
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** findAndReplace: atomically swap the whole document, returning the new one. */
    @PutMapping("/{id}/find-and-replace")
    public ResponseEntity<Product> findAndReplace(@PathVariable String id, @RequestBody Product replacement) {
        Product result = mongoTemplateService.findAndReplaceById(id, replacement);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    /** bulkOps: a batched updateMulti + upsert in one round-trip. */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestParam(defaultValue = "electronics") String category) {
        return mongoTemplateService.bulkMixedWrite(category);
    }

    /** Pipeline update: set inventoryValue = price * stock on every document. */
    @PostMapping("/compute-inventory-value")
    public Map<String, Object> computeInventoryValue() {
        return Map.of("modified", mongoTemplateService.computeInventoryValue());
    }

    @PostMapping("/{id}/tags/add")
    public Map<String, Object> addTag(@PathVariable String id, @RequestParam String tag) {
        return Map.of("modified", mongoTemplateService.addTag(id, tag)); // $addToSet
    }

    @PostMapping("/{id}/tags/push")
    public Map<String, Object> pushTag(@PathVariable String id, @RequestParam String tag) {
        return Map.of("modified", mongoTemplateService.pushTag(id, tag)); // $push
    }

    @PostMapping("/{id}/tags/pull")
    public Map<String, Object> pullTag(@PathVariable String id, @RequestParam String tag) {
        return Map.of("modified", mongoTemplateService.pullTag(id, tag)); // $pull
    }

    @PostMapping("/{id}/unset")
    public Map<String, Object> unset(@PathVariable String id, @RequestParam String field) {
        return Map.of("modified", mongoTemplateService.unsetField(id, field)); // $unset
    }

    @PostMapping("/{id}/min-price")
    public Map<String, Object> minPrice(@PathVariable String id, @RequestParam BigDecimal price) {
        return Map.of("modified", mongoTemplateService.minPrice(id, price)); // $min
    }

    @PostMapping("/{id}/touch")
    public Map<String, Object> touch(@PathVariable String id) {
        return Map.of("modified", mongoTemplateService.touch(id)); // $currentDate
    }
}
