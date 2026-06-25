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
 * A second persisted collection. Originally this entity predated any audit/version work; it now
 * carries the same cross-cutting fields as {@link Product} — a nullable {@code @Version} plus the
 * four audit fields — so optimistic locking and auditing hold on every write path. Ids are
 * server-assigned (a plain {@code @Id String} populated by Mongo).
 */
@Document(collection = "orders")
public class Order {

    @Id
    private String id;

    private String customerId;

    private BigDecimal total;

    private String status;

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

    public Order() {
    }

    public Order(String id, String customerId, BigDecimal total, String status) {
        this.id = id;
        this.customerId = customerId;
        this.total = total;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        return "Order{id=%s, customerId=%s, total=%s, status=%s, version=%s}"
                .formatted(id, customerId, total, status, version);
    }
}
