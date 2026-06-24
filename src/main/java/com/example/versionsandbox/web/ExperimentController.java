package com.example.versionsandbox.web;

import java.math.BigDecimal;
import java.util.Map;

import com.example.versionsandbox.service.ExperimentService;
import com.example.versionsandbox.service.MongoTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The interactive playground. The key probe is: insert a version-less ("legacy") document with
 * {@code POST /legacy-doc}, then {@code POST /{id}/load-then-save} and {@code /{id}/concurrent-update}
 * to see how the current branch's versioning strategy copes with the missing field.
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

    /**
     * Write a document straight to BSON with NO version field — this is what existing production
     * data looks like. On a branch that adds {@code @Version}, this is the document whose migration
     * (without a back-fill) you are testing.
     */
    @PostMapping("/legacy-doc")
    public Map<String, Object> createLegacyDocument(@RequestParam String name,
                                                    @RequestParam(defaultValue = "misc") String category,
                                                    @RequestParam(defaultValue = "9.99") BigDecimal price,
                                                    @RequestParam(defaultValue = "1") int stock) {
        String id = mongoTemplateService.insertLegacyDocumentWithoutVersion(name, category, price, stock);
        return Map.of("id", id, "note", "Inserted WITHOUT a version field. Try POST /api/experiments/" + id + "/load-then-save");
    }

    /** Load by id, then save back. The outcome reveals how this branch handles a version-less doc. */
    @PostMapping("/{id}/load-then-save")
    public Map<String, Object> loadThenSave(@PathVariable String id) {
        return experimentService.loadThenSave(id);
    }

    /** Two writers race on the same document. Behaviour depends on the branch's strategy. */
    @PostMapping("/{id}/concurrent-update")
    public Map<String, Object> concurrentUpdate(@PathVariable String id) {
        return experimentService.simulateConcurrentUpdate(id);
    }

    /**
     * Save a brand-new document whose id is set <em>before</em> saving (client-assigned id). The doc
     * is new but its id is non-null — the case a version-based "is new?" check can get wrong.
     */
    @PostMapping("/save-new-with-id")
    public Map<String, Object> saveNewWithId(@RequestParam String name,
                                             @RequestParam(defaultValue = "misc") String category,
                                             @RequestParam(defaultValue = "9.99") BigDecimal price,
                                             @RequestParam(defaultValue = "1") int stock) {
        return experimentService.saveNewWithPresetId(name, category, price, stock);
    }

    /**
     * Save a brand-new document (non-existent id) that has BOTH a preset id and a preset version —
     * the "object built with id and version" case. The doc does not exist; a non-null version usually
     * means "existing entity at version N".
     */
    @PostMapping("/save-new-with-id-and-version")
    public Map<String, Object> saveNewWithIdAndVersion(@RequestParam String name,
                                                       @RequestParam(defaultValue = "misc") String category,
                                                       @RequestParam(defaultValue = "9.99") BigDecimal price,
                                                       @RequestParam(defaultValue = "1") int stock,
                                                       @RequestParam(defaultValue = "5") Long version) {
        return experimentService.saveNewWithPresetIdAndVersion(name, category, price, stock, version);
    }

    // ---- Lower-level MongoTemplate update operations ----

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

    /** Wipe the collection so you can start an experiment from scratch. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        mongoTemplateService.dropCollection();
        return Map.of("status", "products collection dropped");
    }

    /** Convenience GET so a browser can confirm the app is up. */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok");
    }
}
