package com.example.versionsandbox.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import org.springframework.stereotype.Service;

/**
 * Repository-driven CRUD. Every {@code save()} here runs through Spring Data's version routing,
 * so this is the "normal application code" half of the sandbox.
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product create(Product product) {
        Instant now = Instant.now();
        product.setId(null);
        product.setVersion(null);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        return repository.save(product);
    }

    public List<Product> findAll() {
        return repository.findAll();
    }

    public Optional<Product> findById(String id) {
        return repository.findById(id);
    }

    public List<Product> findByCategory(String category) {
        return repository.findByCategory(category);
    }

    public List<Product> findLegacyWithoutVersion() {
        return repository.findLegacyWithoutVersion();
    }

    /**
     * Load-modify-save. On an existing document this increments {@code @Version}; if another writer
     * has bumped the version since we read it, {@code save()} throws
     * {@code OptimisticLockingFailureException}.
     */
    public Product update(String id, Product changes) {
        Product existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No product with id " + id));
        if (changes.getName() != null) {
            existing.setName(changes.getName());
        }
        if (changes.getCategory() != null) {
            existing.setCategory(changes.getCategory());
        }
        if (changes.getPrice() != null) {
            existing.setPrice(changes.getPrice());
        }
        existing.setStock(changes.getStock());
        existing.setUpdatedAt(Instant.now());
        return repository.save(existing);
    }

    public void deleteById(String id) {
        repository.deleteById(id);
    }

    public long count() {
        return repository.count();
    }
}
