package com.example.versionsandbox.repository;

import java.math.BigDecimal;
import java.util.List;

import com.example.versionsandbox.domain.Product;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The {@code MongoRepository} side of the sandbox.
 *
 * <p>On the baseline (no {@code @Version}), {@code save()} of an existing document is a plain
 * by-{@code _id} upsert. The {@code approach/*} branches add a version field, after which this same
 * {@code save()} starts making insert-vs-update decisions based on the version value.
 */
public interface ProductRepository extends MongoRepository<Product, String> {

    List<Product> findByCategory(String category);

    List<Product> findByPriceGreaterThanEqual(BigDecimal price);

    List<Product> findByStockLessThan(int stock);

    long countByCategory(String category);
}
