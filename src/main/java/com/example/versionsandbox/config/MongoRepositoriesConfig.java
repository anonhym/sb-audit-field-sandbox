package com.example.versionsandbox.config;

import com.example.versionsandbox.repository.ProductRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * approach/explicit-upsert: register {@link UpsertMongoRepository} as the base class for all Mongo
 * repositories, so {@link ProductRepository#save} uses the explicit {@code replaceOne} path.
 *
 * <p>Declaring {@code @EnableMongoRepositories} here also switches off Spring Boot's auto-configured
 * repository scanning, so this is the single place that controls repository wiring on this branch.
 */
@Configuration
@EnableMongoRepositories(
        basePackageClasses = ProductRepository.class,
        repositoryBaseClass = UpsertMongoRepository.class)
public class MongoRepositoriesConfig {
}
