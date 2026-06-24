package com.example.versionsandbox.repository;

import java.math.BigDecimal;
import java.util.List;

import com.example.versionsandbox.domain.Product;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * The {@code MongoRepository} side of the sandbox.
 *
 * <p>{@code save()} here is what exercises the {@link com.example.versionsandbox.domain.Product#getVersion()
 * version} routing: Spring Data inspects the {@code @Version} field to decide between an
 * insert and a version-checked update. Compare against
 * {@link com.example.versionsandbox.service.MongoTemplateService} which uses the lower-level
 * {@code MongoTemplate} API.
 */
public interface ProductRepository extends MongoRepository<Product, String> {

    List<Product> findByCategory(String category);

    List<Product> findByPriceGreaterThanEqual(BigDecimal price);

    List<Product> findByStockLessThan(int stock);

    /** Documents missing the version field entirely — the "legacy" docs to migrate. */
    @Query("{ 'version' : { $exists : false } }")
    List<Product> findLegacyWithoutVersion();

    long countByCategory(String category);
}
