package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * approach/custom-isnew — {@code @Version} plus {@link Persistable}, so the new-vs-existing decision
 * is driven by the <em>id</em>, not by the version value.
 *
 * <p>By default Spring Data treats a null version as "new" and inserts (the {@code approach/naive}
 * break). Here {@link #isNew()} returns {@code id == null}, so a loaded legacy document — which has
 * an {@code _id} but no version — is considered existing and {@code save()} takes the update path.
 * Because the loaded version is {@code null}, the optimistic-update filter is {@code {_id, version:
 * null}}, and in MongoDB {@code version: null} also matches a document where the field is absent — so
 * the update finds the legacy document and stamps a version onto it. New documents (id {@code null})
 * still insert normally.
 *
 * <p>Spring Data MongoDB uses field access, so the parameterless {@link #isNew()} method is not a
 * persistent property and is never written to the document.
 */
@Document(collection = "products")
public class Product implements Persistable<String> {

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

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** An entity is new only when it has no id yet; a null version no longer means "new". */
    @Override
    public boolean isNew() {
        return id == null;
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
