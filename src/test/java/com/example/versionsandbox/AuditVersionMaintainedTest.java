package com.example.versionsandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Behavioural half of the guarantee (ArchUnit can't prove this): version increments and the audit
 * fields stay correct across the {@code save()} path AND a {@code MongoTemplate} update, and a
 * concurrent update raises {@code OptimisticLockingFailureException}.
 *
 * <p>Runs against {@code mongo:8} via Testcontainers, wired in through {@code @ServiceConnection}. The
 * docker-compose lifecycle is disabled here (see test {@code application.properties}) so this test owns
 * the Mongo it talks to.
 */
@SpringBootTest
@Testcontainers
class AuditVersionMaintainedTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired ProductRepository repository;
    @Autowired MongoTemplate mongoTemplate;

    @Test
    void savePathStampsVersionAndAudit() {
        Product saved = repository.save(new Product(null, "Keyboard", "electronics", new BigDecimal("49.90"), 5));

        assertThat(saved.getVersion()).isZero();                 // @Version starts at 0
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Product loaded = repository.findById(saved.getId()).orElseThrow();
        loaded.setStock(6);
        Product updated = repository.save(loaded);

        assertThat(updated.getVersion()).isEqualTo(1L);          // increments on save() update
        // created* is KEPT (not reset) on update. Compare at millisecond precision: BSON dates store
        // only milliseconds, so the value read back from Mongo is truncated relative to the in-memory
        // Instant — asserting nanosecond-exact equality across that boundary is a test bug (A1).
        assertThat(updated.getCreatedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(saved.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void templateUpdateMaintainsVersionAndUpdatedFields() {
        Product saved = repository.save(new Product(null, "Mouse", "electronics", new BigDecimal("19.90"), 10));

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("stock", 9),                     // caller sets only stock...
                Product.class);

        Product after = repository.findById(saved.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(1L);             // Spring Data 5.x auto-increments
        assertThat(after.getUpdatedAt()).isAfterOrEqualTo(saved.getUpdatedAt()); // ...aspect set updatedAt
        assertThat(after.getUpdatedBy()).isNotNull();             // ...aspect set updatedBy
        // created* untouched by the template update (millisecond precision — see note above).
        assertThat(after.getCreatedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(saved.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void concurrentUpdateRaisesOptimisticLock() {
        Product saved = repository.save(new Product(null, "Cable", "electronics", new BigDecimal("4.90"), 100));

        Product a = repository.findById(saved.getId()).orElseThrow();
        Product b = repository.findById(saved.getId()).orElseThrow(); // same version as a

        a.setStock(99);
        repository.save(a);                                       // version 0 -> 1

        b.setStock(98);
        assertThatThrownBy(() -> repository.save(b))              // stale version 0 -> conflict
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
