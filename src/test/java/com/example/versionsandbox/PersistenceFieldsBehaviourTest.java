package com.example.versionsandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import com.example.versionsandbox.config.CurrentUser;
import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import com.example.versionsandbox.service.ExperimentService;
import com.example.versionsandbox.service.MongoTemplateService;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Behavioural proof, against a real MongoDB, that the five cross-cutting fields are maintained on the
 * write paths — and, critically, that legacy version-less documents migrate lazily (no back-fill).
 *
 * <p>Docker Compose and the startup seeder are disabled so the test owns the dataset; Mongo is
 * provided by Testcontainers.
 */
@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.seed.enabled=false"
})
@Testcontainers
class PersistenceFieldsBehaviourTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired ProductRepository repository;
    @Autowired MongoTemplateService templateService;
    @Autowired ExperimentService experimentService;

    @BeforeEach
    void resetUser() {
        CurrentUser.set("alice");
    }

    @AfterEach
    void cleanUp() {
        CurrentUser.clear();
        templateService.dropCollection();
    }

    // ----------------------------------------------------------------------
    // @Version
    // ----------------------------------------------------------------------

    @Test
    void saveOfNewDocumentStampsVersionZeroAndAuditFields() {
        Product saved = repository.save(new Product(null, "Keyboard", "electronics", new BigDecimal("99"), 5));

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getUpdatedBy()).isEqualTo("alice");
    }

    @Test
    void legacyVersionlessDocumentMigratesOnNextSaveWithoutBackfill() {
        // A document written before @Version existed: no version field at all.
        String id = templateService.insertLegacyDocumentWithoutVersion("Legacy", "misc", new BigDecimal("1"), 1);
        assertThat(templateService.storedVersion(id)).isNull();

        // The whole point: load-then-save must NOT throw DuplicateKeyException; it migrates to version 0.
        Map<String, Object> result = experimentService.loadThenSave(id);

        assertThat(result.get("outcome")).isEqualTo("SAVED");
        assertThat(templateService.storedVersion(id)).isEqualTo(0L);
    }

    @Test
    void concurrentUpdatesAreOptimisticallyLocked() {
        Product created = repository.save(new Product(null, "Mouse", "electronics", new BigDecimal("25"), 10));
        String id = created.getId();

        Product writerA = repository.findById(id).orElseThrow();
        Product writerB = repository.findById(id).orElseThrow();

        writerA.setStock(11);
        repository.save(writerA); // version 0 -> 1

        writerB.setStock(99); // still holds version 0
        assertThatThrownBy(() -> repository.save(writerB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void brandNewDocumentWithClientAssignedIdIsInserted() {
        // New doc, id set before save, version null: must INSERT (not throw), and be versioned.
        String id = new ObjectId().toHexString();
        Product product = new Product(id, "Preset", "misc", new BigDecimal("5"), 1);

        Product saved = repository.save(product);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(templateService.storedVersion(id)).isEqualTo(0L);
        assertThat(repository.findById(id)).isPresent();
        assertThat(templateService.auditFields(id).get("createdBy")).isEqualTo("alice");
    }

    // ----------------------------------------------------------------------
    // Audit on the MongoTemplate write paths (the AOP aspect)
    // ----------------------------------------------------------------------

    @Test
    void updateFirstMaintainsUpdatedByOnTemplatePath() {
        CurrentUser.set("alice");
        Product created = repository.save(new Product(null, "Cable", "electronics", new BigDecimal("12"), 100));
        String id = created.getId();

        // A different user performs a low-level template update.
        CurrentUser.set("bob");
        templateService.incrementStock(id, 5);

        Map<String, Object> audit = templateService.auditFields(id);
        // The baseline bug was updatedBy going stale here; the aspect must refresh it to "bob",
        // while preserving the original creator.
        assertThat(audit.get("updatedBy")).isEqualTo("bob");
        assertThat(audit.get("createdBy")).isEqualTo("alice");
        assertThat(audit.get("updatedAt")).isNotNull();
    }

    @Test
    void templateUpsertInsertPopulatesCreatedAndUpdatedBy() {
        CurrentUser.set("carol");
        // Upsert by a name that does not exist yet -> the insert branch.
        Map<String, Object> result =
                templateService.upsertByName("Fresh Upsert", "misc", new BigDecimal("3"), 7);

        String id = String.valueOf(result.get("upsertedId"));
        assertThat(id).isNotEqualTo("null");

        Map<String, Object> audit = templateService.auditFields(id);
        assertThat(audit.get("createdBy")).isEqualTo("carol");
        assertThat(audit.get("updatedBy")).isEqualTo("carol");
        assertThat(audit.get("createdAt")).isNotNull();
        assertThat(audit.get("updatedAt")).isNotNull();
    }
}
