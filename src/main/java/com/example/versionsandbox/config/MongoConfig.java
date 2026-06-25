package com.example.versionsandbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Wires the two cross-cutting persistence mechanisms:
 * <ul>
 *   <li>the {@code upsert-all-cases} version-migration strategy, by making
 *       {@link UpsertingMongoRepository} the base class for <em>every</em> repository;</li>
 *   <li>AspectJ auto-proxying so {@link AuditTemplateAspect} can advise the {@code MongoTemplate}
 *       write paths. {@code proxyTargetClass = true} is required because callers inject the concrete
 *       {@code MongoTemplate} type, so the proxy must be a CGLIB subclass, not a JDK interface proxy.</li>
 * </ul>
 * The base package mirrors Spring Boot's default scan ({@code com.example.versionsandbox}).
 */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableMongoRepositories(
        basePackages = "com.example.versionsandbox",
        repositoryBaseClass = UpsertingMongoRepository.class)
public class MongoConfig {
}
