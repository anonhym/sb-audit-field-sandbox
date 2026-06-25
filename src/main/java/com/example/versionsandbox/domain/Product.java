package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The sandbox aggregate — <strong>audit baseline</strong>.
 *
 * <p>Carries the four audit fields ({@code createdAt}, {@code createdBy}, {@code updatedAt},
 * {@code updatedBy}) wired to Spring Data's auditing annotations. With {@code @EnableMongoAuditing}
 * active (see {@code config.AuditingConfig}) these are populated automatically — but <em>only on the
 * {@code save()} / entity-conversion path</em>. The lower-level {@code MongoTemplate} writes
 * ({@code updateFirst}, {@code updateMulti}, {@code upsert}, {@code findAndModify}, {@code bulkOps},
 * {@code findAndReplace}) do <em>not</em> trigger auditing, so they leave these fields stale.
 *
 * <p>This is the same kind of baseline used for the version investigation: the harness reads the audit
 * fields from the stored BSON (not off this class), so it can show which write paths maintain them.
 * The {@code archunit/*} and {@code aop/*} branches then make the audit fields impossible to forget.
 *
 * <p>Note the asymmetry with {@code @Version}: Spring Data 5.x auto-increments the version on the
 * template update methods, but it does NOT apply auditing on those same methods.
 */
@Document(collection = "products")
public class Product {

    @Id
    private String id;

    private String name;

    private String category;

    private BigDecimal price;

    private int stock;

    /**
     * Optimistic-locking version. Nullable {@code Long} on purpose: a legacy document written before
     * this field existed loads as {@code null}, and the {@code upsert-all-cases} repository strategy
     * relies on that null to migrate the doc to {@code version = 0} on its next save without a
     * back-fill (the update filter {@code {_id, version:null}} matches an absent field).
     */
    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @CreatedBy
    private String createdBy;

    @LastModifiedDate
    private Instant updatedAt;

    @LastModifiedBy
    private String updatedBy;

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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public String toString() {
        return "Product{id=%s, name=%s, category=%s, price=%s, stock=%d, createdBy=%s, updatedBy=%s}"
                .formatted(id, name, category, price, stock, createdBy, updatedBy);
    }
}
