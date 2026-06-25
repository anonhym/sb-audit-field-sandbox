package com.example.versionsandbox.web;

import java.util.List;
import java.util.Map;

import com.example.versionsandbox.service.MongoTemplateService;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only inspection endpoints shared by every branch. On the baseline {@code version-stats}
 * reports that <em>all</em> documents are missing the version field; on an {@code approach/*} branch
 * it shows how many have been migrated as you exercise the strategy.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final MongoTemplateService mongoTemplateService;

    public StatusController(MongoTemplateService mongoTemplateService) {
        this.mongoTemplateService = mongoTemplateService;
    }

    /** Total docs, how many lack a version field, and the distribution of version values. */
    @GetMapping("/version-stats")
    public Map<String, Object> versionStats() {
        return mongoTemplateService.versionStats();
    }

    /** Aggregation pipeline: document counts per category. */
    @GetMapping("/count-by-category")
    public List<Document> countByCategory() {
        return mongoTemplateService.countByCategory();
    }

    /** The four audit fields exactly as stored — to see which write paths maintained them. */
    @GetMapping("/audit/{id}")
    public Map<String, Object> audit(@PathVariable String id) {
        return mongoTemplateService.auditFields(id);
    }
}
