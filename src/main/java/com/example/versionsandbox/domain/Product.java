package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The sandbox aggregate — <strong>baseline (no version field)</strong>.
 *
 * <p>This is "production as it is today": documents are stored with no {@code version} field, and
 * the entity has no {@code @Version} property. Saving an existing document is a plain by-{@code _id}
 * upsert with last-write-wins semantics and <em>no</em> optimistic locking.
 *
 * <p>Each {@code approach/*} branch adds {@code @Version} via a different strategy to migrate the
 * existing version-less documents <em>without a bulk back-fill</em> (a business restriction). The
 * shared test harness in this project never reads {@code version} off this class — it reads it from
 * the stored BSON — so the harness compiles unchanged on this baseline and on every branch.
 */
@Document(collection = "products")
public class Product {

    @Id
    private String id;

    private String name;

    private String category;

    private BigDecimal price;

    private int stock;

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
        return "Product{id=%s, name=%s, category=%s, price=%s, stock=%d}"
                .formatted(id, name, category, price, stock);
    }
}
