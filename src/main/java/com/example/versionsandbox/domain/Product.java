package com.example.versionsandbox.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The sandbox aggregate.
 *
 * <p>The interesting field is {@link #version}, annotated with Spring Data's
 * {@link Version @Version}. It is a wrapper {@link Long} (not a primitive) on purpose:
 * with a wrapper type, Spring Data treats {@code null} as "this entity is new" and any
 * non-null value (including {@code 0}) as "this entity already exists". That single
 * distinction is what drives every migration behaviour we want to observe:
 *
 * <ul>
 *   <li>A document written <em>without</em> a {@code version} field loads back with
 *       {@code version == null}, so a subsequent {@code save()} is routed to an
 *       <strong>insert</strong> and blows up with a duplicate-key error.</li>
 *   <li>Back-filling the field to {@code 0} (matching what a native insert stores) makes
 *       the same {@code save()} take the <strong>update</strong> path and increment the
 *       version normally.</li>
 *   <li>Two updates racing on the same loaded version cause the second {@code save()} to
 *       throw {@code OptimisticLockingFailureException}.</li>
 * </ul>
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
