package com.example.versionsandbox.service;

import java.util.List;
import java.util.Optional;

import com.example.versionsandbox.domain.Product;
import com.example.versionsandbox.repository.ProductRepository;
import org.springframework.stereotype.Service;

/**
 * Repository-driven CRUD. Every {@code save()} here runs through Spring Data auditing, so the four
 * audit fields are populated automatically — no manual timestamp/user wiring needed on this path.
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product create(Product product) {
        product.setId(null);
        return repository.save(product); // @CreatedDate/@CreatedBy/@LastModified* set by auditing
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

    /** Load-modify-save through the repository — the one write path Spring Data auditing covers. */
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
        return repository.save(existing); // @LastModifiedDate/@LastModifiedBy refreshed by auditing
    }

    public void deleteById(String id) {
        repository.deleteById(id);
    }

    public long count() {
        return repository.count();
    }
}
