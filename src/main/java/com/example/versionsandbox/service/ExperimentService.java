package com.example.versionsandbox.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Scripted probes that behave differently depending on the versioning strategy of the current
 * branch. They never touch a {@code version} property on {@link Product} — the version is read from
 * the stored BSON via {@link MongoTemplateService#storedVersion(String)} — so this exact class
 * compiles and runs on the no-version baseline <em>and</em> on every {@code approach/*} branch. The
 * differences you see between branches come entirely from how {@code save()} is wired, not from
 * this harness.
 */
@Service
public class ExperimentService {

    private final ProductRepository repository;
    private final MongoTemplateService mongoTemplateService;

    public ExperimentService(ProductRepository repository, MongoTemplateService mongoTemplateService) {
        this.repository = repository;
        this.mongoTemplateService = mongoTemplateService;
    }

    /**
     * Load a document, then {@code save()} it back through the repository, reporting the stored
     * version before and after and any exception. This is the single most revealing probe for a
     * branch: run it against a version-less ("legacy") document to see how that branch's strategy
     * copes with the missing field.
     */
    public Map<String, Object> loadThenSave(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        Product loaded = repository.findById(id).orElse(null);
        if (loaded == null) {
            result.put("outcome", "NOT_FOUND");
            return result;
        }
        result.put("storedVersionBefore", mongoTemplateService.storedVersion(id));
        loaded.setUpdatedAt(Instant.now());
        try {
            repository.save(loaded);
            result.put("outcome", "SAVED");
            result.put("storedVersionAfter", mongoTemplateService.storedVersion(id));
        } catch (RuntimeException ex) {
            result.put("outcome", "FAILED");
            result.put("exception", ex.getClass().getName());
            result.put("message", rootMessage(ex));
        }
        return result;
    }

    /**
     * Two writers read the same document, both modify it, both save. On the baseline both saves
     * succeed (last-write-wins). On a branch that adds {@code @Version}, the second save should hit
     * {@code OptimisticLockingFailureException} — unless that branch's strategy gives up locking.
     */
    public Map<String, Object> simulateConcurrentUpdate(String id) {
        Map<String, Object> result = new LinkedHashMap<>();

        Product writerA = repository.findById(id).orElse(null);
        Product writerB = repository.findById(id).orElse(null);
        if (writerA == null || writerB == null) {
            result.put("outcome", "NOT_FOUND");
            return result;
        }
        result.put("storedVersionBefore", mongoTemplateService.storedVersion(id));

        writerA.setStock(writerA.getStock() + 1);
        result.put("writerA", saveAndReport(writerA, id));

        writerB.setStock(writerB.getStock() + 100);
        result.put("writerB", saveAndReport(writerB, id));

        result.put("storedVersionAfter", mongoTemplateService.storedVersion(id));
        return result;
    }

    private Map<String, Object> saveAndReport(Product product, String id) {
        try {
            repository.save(product);
            return Map.of("outcome", "SAVED", "storedVersionAfter", String.valueOf(mongoTemplateService.storedVersion(id)));
        } catch (OptimisticLockingFailureException ex) {
            return Map.of("outcome", "OPTIMISTIC_LOCK_FAILURE",
                    "exception", ex.getClass().getName(),
                    "message", String.valueOf(ex.getMessage()));
        } catch (RuntimeException ex) {
            return Map.of("outcome", "FAILED",
                    "exception", ex.getClass().getName(),
                    "message", rootMessage(ex));
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
