package com.example.versionsandbox.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.example.versionsandbox.service.ExperimentService;
import com.example.versionsandbox.service.MongoTemplateService;
import org.bson.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The interactive playground for {@code @Version} behaviour and the lower-level {@code MongoTemplate}
 * update operations. These endpoints deliberately let you create a "broken" state (a legacy document
 * with no version field) and then watch the different ways it behaves.
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;
    private final MongoTemplateService mongoTemplateService;

    public ExperimentController(ExperimentService experimentService, MongoTemplateService mongoTemplateService) {
        this.experimentService = experimentService;
        this.mongoTemplateService = mongoTemplateService;
    }

    /** Write a document with NO version field (simulates data created before {@code @Version} existed). */
    @PostMapping("/legacy-doc")
    public Map<String, Object> createLegacyDocument(@RequestParam String name,
                                                    @RequestParam(defaultValue = "misc") String category,
                                                    @RequestParam(defaultValue = "9.99") BigDecimal price,
                                                    @RequestParam(defaultValue = "1") int stock) {
        String id = mongoTemplateService.insertLegacyDocumentWithoutVersion(name, category, price, stock);
        return Map.of("id", id, "note", "Inserted WITHOUT a version field. Try POST /api/experiments/" + id + "/load-then-save");
    }

    /** Load by id then save — succeeds for versioned docs, fails with duplicate-key for legacy docs. */
    @PostMapping("/{id}/load-then-save")
    public Map<String, Object> loadThenSave(@PathVariable String id) {
        return experimentService.loadThenSave(id);
    }

    /** Two writers race on the same version; the second should hit an optimistic-lock failure. */
    @PostMapping("/{id}/concurrent-update")
    public Map<String, Object> concurrentUpdate(@PathVariable String id) {
        return experimentService.simulateConcurrentUpdate(id);
    }

    // ---- Lower-level MongoTemplate update operations (bypass optimistic locking) ----

    @PostMapping("/{id}/inc-stock")
    public Map<String, Object> incStock(@PathVariable String id, @RequestParam(defaultValue = "1") int delta) {
        long modified = mongoTemplateService.incrementStock(id, delta);
        return Map.of("modified", modified);
    }

    @PostMapping("/raise-prices")
    public Map<String, Object> raisePrices(@RequestParam String category,
                                           @RequestParam(defaultValue = "1.10") BigDecimal multiplier) {
        long modified = mongoTemplateService.raisePricesInCategory(category, multiplier);
        return Map.of("modified", modified);
    }

    @GetMapping("/count-by-category")
    public List<Document> countByCategory() {
        return mongoTemplateService.countByCategory();
    }

    /** Wipe the collection so you can start an experiment from scratch. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        mongoTemplateService.dropCollection();
        return Map.of("status", "products collection dropped");
    }
}
