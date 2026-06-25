// TEMPLATE — copy into your project's test sources and adapt the entity/repository names + package.
// Proves the BEHAVIOUR the aspect + strategy are responsible for (ArchUnit can't): version increments
// and audit fields stay correct across the save() path AND a MongoTemplate update. Requires
// Testcontainers (spring-boot-testcontainers + testcontainers-junit-jupiter + testcontainers-mongodb;
// on Spring Boot 4.1 these resolve to Testcontainers 2.x, whose modules are named
// `testcontainers-junit-jupiter` / `testcontainers-mongodb`, not `junit-jupiter` / `mongodb`).
package com.yourorg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

import com.yourorg.domain.Product;            // TODO: your audited+versioned entity
import com.yourorg.repository.ProductRepository; // TODO: its repository
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

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
        // created is kept — compare at millisecond precision: Mongo stores BSON dates as millis, so the
        // value read back loses the in-memory Instant's sub-millisecond nanos.
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
        assertThat(after.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)) // created untouched (BSON millis)
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
