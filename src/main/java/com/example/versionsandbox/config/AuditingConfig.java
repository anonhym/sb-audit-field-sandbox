package com.example.versionsandbox.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Enables Spring Data MongoDB auditing. With this on, an entity's {@code @CreatedDate},
 * {@code @CreatedBy}, {@code @LastModifiedDate} and {@code @LastModifiedBy} fields are populated
 * automatically — but <strong>only on the {@code save()} path</strong> (via the auditing
 * {@code BeforeConvertCallback}). {@code MongoTemplate} update operations bypass it entirely.
 */
@Configuration
@EnableMongoAuditing
public class AuditingConfig {

    /** Supplies {@code @CreatedBy} / {@code @LastModifiedBy} from the per-request {@link CurrentUser}. */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(CurrentUser.get());
    }
}
