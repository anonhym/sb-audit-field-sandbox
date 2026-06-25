package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A second persisted collection that — unlike {@link Product} — predates any audit/version work:
 * no {@code @Version}, none of the four audit fields. Ids are server-assigned (a plain {@code @Id
 * String} populated by Mongo). This is the "legacy entity that never got the cross-cutting fields"
 * case a refactor has to bring up to standard.
 */
@Document(collection = "orders")
public class Order implements Persistable<String> {

    @Id
    private String id;

    private String customerId;

    private BigDecimal total;

    private String status;

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

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * An entity is new only when it has no id yet; a null {@code version} no longer means "new".
     * This lets {@code save()} treat a loaded legacy (version-less) document as an update — the
     * {@code {_id, version:null}} filter matches the existing doc and stamps {@code version = 0}.
     */
    @Override
    public boolean isNew() {
        return id == null;
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

    @Override
    public String toString() {
        return "Order{id=%s, customerId=%s, total=%s, status=%s}".formatted(id, customerId, total, status);
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
}
