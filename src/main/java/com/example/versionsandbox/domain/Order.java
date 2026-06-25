package com.example.versionsandbox.domain;

import java.math.BigDecimal;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A second persisted collection that — unlike {@link Product} — predates any audit/version work:
 * no {@code @Version}, none of the four audit fields. Ids are server-assigned (a plain {@code @Id
 * String} populated by Mongo). This is the "legacy entity that never got the cross-cutting fields"
 * case a refactor has to bring up to standard.
 */
@Document(collection = "orders")
public class Order {

    @Id
    private String id;

    private String customerId;

    private BigDecimal total;

    private String status;

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

    @Override
    public String toString() {
        return "Order{id=%s, customerId=%s, total=%s, status=%s}".formatted(id, customerId, total, status);
    }
}
