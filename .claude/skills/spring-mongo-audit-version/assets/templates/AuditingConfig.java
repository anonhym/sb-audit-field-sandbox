// TEMPLATE — copy into your project and adjust the package + the auditor source.
// Add this ONLY if @EnableMongoAuditing is not already present somewhere in the app.
package com.yourorg.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Enables Spring Data MongoDB auditing. With this on, an entity's {@code @CreatedDate},
 * {@code @CreatedBy}, {@code @LastModifiedDate} and {@code @LastModifiedBy} fields are populated
 * automatically — but <strong>only on the {@code save()} / entity-conversion path</strong> (via the
 * auditing {@code BeforeConvertCallback}). {@code MongoTemplate} update operations bypass it entirely,
 * which is why the {@code AuditFieldsAspect} exists.
 */
@Configuration
@EnableMongoAuditing
public class AuditingConfig {

    /**
     * Supplies {@code @CreatedBy} / {@code @LastModifiedBy}. In a real service this is your security
     * context (e.g. {@code SecurityContextHolder.getContext().getAuthentication().getName()}). The
     * template below reads a request-scoped {@link CurrentUser}; wire the AOP aspect to the SAME
     * source so the save() path and the template path agree.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(CurrentUser.get());
    }
}
