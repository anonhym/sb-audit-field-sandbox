package com.example.versionsandbox.web;

import java.util.Map;

import com.example.versionsandbox.service.MongoTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The migration-path endpoints. Typical run:
 * <ol>
 *   <li>{@code GET  /api/migrations/version-stats} — see how many docs are missing the version field</li>
 *   <li>{@code POST /api/migrations/backfill-version} — set {@code version = 0} on those docs</li>
 *   <li>{@code GET  /api/migrations/version-stats} — confirm the field is now present everywhere</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final MongoTemplateService mongoTemplateService;

    public MigrationController(MongoTemplateService mongoTemplateService) {
        this.mongoTemplateService = mongoTemplateService;
    }

    @GetMapping("/version-stats")
    public Map<String, Object> versionStats() {
        return mongoTemplateService.versionStats();
    }

    @PostMapping("/backfill-version")
    public Map<String, Object> backfillVersion() {
        long migrated = mongoTemplateService.backfillMissingVersions();
        return Map.of("migratedDocuments", migrated);
    }
}
