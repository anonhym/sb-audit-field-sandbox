// TEMPLATE — copy into your project and adjust the package + basePackageClasses.
// Only needed for the client-assigned-id strategy alongside UpsertMongoRepository.
package com.yourorg.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Registers {@link UpsertMongoRepository} as the base class for all Mongo repositories, so every
 * {@code repository.save(...)} uses the explicit upsert/optimistic-lock path. Point
 * {@code basePackageClasses} at one of your repository interfaces (or use {@code basePackages}).
 *
 * <p>Declaring {@code @EnableMongoRepositories} here switches off Spring Boot's auto-configured
 * repository scanning, so this becomes the single place that controls repository wiring.
 */
@Configuration
@EnableMongoRepositories(
        basePackageClasses = com.yourorg.repository.SomeRepository.class,
        repositoryBaseClass = UpsertMongoRepository.class)
public class MongoRepositoriesConfig {
}
