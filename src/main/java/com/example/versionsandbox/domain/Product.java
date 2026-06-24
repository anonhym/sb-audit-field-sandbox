package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * approach/explicit-upsert — {@code @Version} on the entity, but {@code save()} of an existing
 * document is routed through a custom repository base class
 * ({@code com.example.versionsandbox.config.UpsertMongoRepository}) that does an explicit
 * {@code replaceOne(_id, …)} instead of letting Spring Data choose insert-vs-update from the version.
 *
 * <p>Because the write is keyed on {@code _id}, a null version never gets mis-routed to an insert, so
 * legacy version-less documents are replaced (and stamped with a version) rather than failing. The
 * version field here is still used to carry/observe the value and to hand-roll an optimistic check on
 * subsequent writes — see the base class for the trade-off on the very first migrating write.
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
