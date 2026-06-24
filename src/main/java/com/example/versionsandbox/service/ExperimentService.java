package com.example.versionsandbox.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Scripted scenarios that show off {@code @Version} behaviour end-to-end. Each method returns a
 * plain {@code Map} describing what happened so the result is easy to read straight off an HTTP
 * response — including the exception type when a path is <em>supposed</em> to fail.
 */
@Service
public class ExperimentService {

    private final ProductRepository repository;

    public ExperimentService(ProductRepository repository) {
        this.repository = repository;
    }

    /**
     * Load a document, then try to {@code save()} it through the repository.
     *
     * <p>For a normal (versioned) document this updates and bumps the version. For a
     * <em>legacy</em> document with no version field, the loaded {@code version} is {@code null},
     * Spring Data decides the entity "is new", routes to an insert, and the existing {@code _id}
     * triggers a duplicate-key error. This is the classic break you hit when you add
     * {@code @Version} to a model that already has data in production.
     */
    public Map<String, Object> loadThenSave(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        Product loaded = repository.findById(id).orElse(null);
        if (loaded == null) {
            result.put("outcome", "NOT_FOUND");
            return result;
        }
        result.put("loadedVersion", loaded.getVersion());
        loaded.setUpdatedAt(Instant.now());
        try {
            Product saved = repository.save(loaded);
            result.put("outcome", "SAVED");
            result.put("newVersion", saved.getVersion());
        } catch (RuntimeException ex) {
            result.put("outcome", "FAILED");
            result.put("exception", ex.getClass().getName());
            result.put("message", rootMessage(ex));
        }
        return result;
    }

    /**
     * Simulate two writers racing on the same document. Both read the same version, both modify,
     * both save. The first save wins and bumps the version; the second still holds the stale
     * version and fails with {@code OptimisticLockingFailureException}.
     */
    public Map<String, Object> simulateConcurrentUpdate(String id) {
        Map<String, Object> result = new LinkedHashMap<>();

        Product writerA = repository.findById(id).orElse(null);
        Product writerB = repository.findById(id).orElse(null);
        if (writerA == null || writerB == null) {
            result.put("outcome", "NOT_FOUND");
            return result;
        }
        result.put("versionBothRead", writerA.getVersion());

        writerA.setStock(writerA.getStock() + 1);
        Product savedA = repository.save(writerA);
        result.put("writerA", Map.of("outcome", "SAVED", "newVersion", savedA.getVersion()));

        writerB.setStock(writerB.getStock() + 100);
        try {
            Product savedB = repository.save(writerB);
            result.put("writerB", Map.of("outcome", "SAVED", "newVersion", savedB.getVersion()));
        } catch (OptimisticLockingFailureException ex) {
            result.put("writerB", Map.of(
                    "outcome", "OPTIMISTIC_LOCK_FAILURE",
                    "exception", ex.getClass().getName(),
                    "message", String.valueOf(ex.getMessage())));
        }
        return result;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
