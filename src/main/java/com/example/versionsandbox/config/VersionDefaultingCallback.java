package com.example.versionsandbox.config;

import com.example.versionsandbox.domain.Product;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.stereotype.Component;

/**
 * approach/lazy-on-read.
 *
 * <p>Spring Data invokes this callback right after it maps a stored BSON document into a
 * {@link Product}. If the document had no {@code version} field, the mapped entity's version is
 * {@code null}; we default it to {@code 0} here. The intent is that the entity then looks
 * "existing" (non-null version) so the next {@code save()} is routed to an update rather than an
 * insert — migrating the document the first time it is written, with no bulk back-fill.
 *
 * <p><strong>Caveat this branch measures:</strong> a versioned update filters on the loaded version
 * ({@code {_id, version: 0}}). A stored document that has no {@code version} field is <em>not</em>
 * matched by {@code version: 0} (only by {@code version: null}), so the first update may still fail —
 * just with an optimistic-lock error instead of a duplicate key. See {@code NOTES.md}.
 */
@Component
public class VersionDefaultingCallback implements AfterConvertCallback<Product> {

    @Override
    public Product onAfterConvert(Product entity, Document document, String collection) {
        if (entity.getVersion() == null) {
            entity.setVersion(0L);
        }
        return entity;
    }
}
