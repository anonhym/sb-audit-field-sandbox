package com.example.versionsandbox.repository;

import java.util.List;

import com.example.versionsandbox.domain.Order;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link Order}. Plain {@code MongoRepository} — on this legacy branch {@code save()}
 * is a by-{@code _id} upsert with no optimistic locking, and nothing maintains audit fields.
 */
public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(String status);
}
