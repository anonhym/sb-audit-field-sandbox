package com.example.versionsandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.Consumer;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import com.example.versionsandbox.service.MongoTemplateService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runnable catalog of what breaks when {@code @Version} is switched on, and the fix for each — the
 * executable companion to {@code REFACTOR-SCENARIOS.md} (on {@code main}). Each test asserts that the
 * <em>broken</em> pattern fails and the <em>fixed</em> pattern works.
 *
 * <p>Runs against a real Mongo via Testcontainers. This branch is off {@code approach/custom-isnew},
 * so {@code Product} has {@code @Version} + {@code Persistable} — i.e. the post-refactor state.
 */
@SpringBootTest
@Testcontainers
class RefactorScenariosTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final BigDecimal PRICE = new BigDecimal("9.99");
    private static final int MAX_RETRIES = 5;

    @Autowired
    private ProductRepository repository;
    @Autowired
    private MongoTemplate template;
    @Autowired
    private MongoTemplateService mongoTemplateService;

    @BeforeEach
    void clean() {
        template.dropCollection("products");
    }

    @Test
    @DisplayName("V1 fabricated 'existing' entity (preset id+version) -> OLFE; fix: insert then load-modify-save")
    void v1_fabricatedExistingEntity() {
        Product fabricated = new Product(new ObjectId().toHexString(), "Keyboard", "electronics", PRICE, 5);
        fabricated.setVersion(0L); // BAD: pretends a never-saved object is managed
        assertThatThrownBy(() -> repository.save(fabricated))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // FIX: persist a real one, then load -> modify -> save
        Product created = repository.save(new Product(null, "Keyboard", "electronics", PRICE, 5));
        Product loaded = repository.findById(created.getId()).orElseThrow();
        loaded.setStock(6);
        assertThat(repository.save(loaded).getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("V2 save() on a legacy version-less doc migrates (custom-isnew), not DuplicateKey")
    void v2_legacyVersionlessDoc() {
        String id = mongoTemplateService.insertLegacyDocumentWithoutVersion("Legacy", "misc", PRICE, 2);
        assertThat(mongoTemplateService.storedVersion(id)).isNull();

        Product migrated = repository.save(repository.findById(id).orElseThrow());
        assertThat(migrated.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("V3 concurrent load-modify-save -> OLFE (real bug surfaced); fix: optimistic retry")
    void v3_concurrentModification() {
        Product p = repository.save(new Product(null, "Mouse", "electronics", PRICE, 10));
        Product writerA = repository.findById(p.getId()).orElseThrow();
        Product writerB = repository.findById(p.getId()).orElseThrow();
        writerA.setStock(11);
        repository.save(writerA);
        writerB.setStock(99);
        assertThatThrownBy(() -> repository.save(writerB))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // FIX: optimistic-retry loop
        Product retried = saveWithRetry(p.getId(), prod -> prod.setStock(99));
        assertThat(retried.getStock()).isEqualTo(99);
    }

    @Test
    @DisplayName("V4 stale/detached entity saved later -> OLFE; fix: reload before save")
    void v4_staleDetachedEntity() {
        Product p = repository.save(new Product(null, "Cable", "electronics", PRICE, 200));
        Product detached = repository.findById(p.getId()).orElseThrow(); // version 0, held
        Product other = repository.findById(p.getId()).orElseThrow();
        other.setStock(190);
        repository.save(other); // version -> 1
        detached.setStock(180);
        assertThatThrownBy(() -> repository.save(detached))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // FIX: reload, re-apply, save
        Product fresh = repository.findById(p.getId()).orElseThrow();
        fresh.setStock(180);
        assertThat(repository.save(fresh).getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("V5 version is present and increments (replaces old absent/exact assertions)")
    void v5_versionPresentAndIncrements() {
        Product created = repository.save(new Product(null, "Pen", "stationery", PRICE, 50));
        assertThat(created.getVersion()).isZero(); // old test asserted isNull()
        created.setStock(49);
        assertThat(repository.save(created).getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("V6 new entity with id + non-null version (doc doesn't exist) -> OLFE by contract")
    void v6_newEntityWithPresetVersion() {
        Product newWithVersion = new Product(new ObjectId().toHexString(), "Lamp", "home", PRICE, 1);
        newWithVersion.setVersion(3L);
        assertThatThrownBy(() -> repository.save(newWithVersion))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // FIX: a new object carries a null version
        Product fixed = new Product(null, "Lamp", "home", PRICE, 1);
        assertThat(repository.save(fixed).getVersion()).isZero();
    }

    private Product saveWithRetry(String id, Consumer<Product> mutation) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Product current = repository.findById(id).orElseThrow();
            mutation.accept(current);
            try {
                return repository.save(current);
            } catch (OptimisticLockingFailureException retry) {
                // reload and try again
            }
        }
        throw new IllegalStateException("optimistic retries exhausted");
    }
}
