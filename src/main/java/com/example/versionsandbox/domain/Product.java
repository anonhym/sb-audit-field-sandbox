package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * approach/naive — add {@code @Version} and <strong>nothing else</strong>. This is the failure
 * control.
 *
 * <p>Spring Data decides insert-vs-update from the version value: a {@code null} version (which is
 * exactly what an existing, never-back-filled document loads as) is treated as "new", so
 * {@code save()} is routed to an <em>insert</em> and the already-present {@code _id} triggers a
 * duplicate-key error. New documents created through the app are fine (they get {@code version=0}
 * and lock correctly); only the legacy, version-less documents break. The other {@code approach/*}
 * branches exist to fix this without a back-fill.
 */
@Document(collection = "products")
public class Product {

    @Id
    private String id;

    private String name;

    private String category;

    private BigDecimal price;

    private int stock;

    @Version
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    public Product() {
    }

    public Product(String id, String name, String category, BigDecimal price, int stock) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Product{id=%s, name=%s, category=%s, price=%s, stock=%d, version=%s}"
                .formatted(id, name, category, price, stock, version);
    }
}
