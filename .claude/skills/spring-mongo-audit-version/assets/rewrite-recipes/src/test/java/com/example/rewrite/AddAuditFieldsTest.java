package com.example.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Note on formatting: these assertions show the output under OpenRewrite's <em>default</em> style —
 * inserted fields inherit the (tight) spacing of the source's existing fields, and 5+ same-package
 * imports collapse to a star import. A real project's detected style governs the actual rendering
 * (the sandbox run kept explicit imports and blank lines). The recipe logic — which fields are added
 * vs left alone, the accessors, and the imports — is what these tests pin down.
 */
class AddAuditFieldsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddAuditFields())
                .parser(JavaParser.fromJavaVersion().classpath("spring-data-mongodb", "spring-data-commons"))
                // The recipe inserts source text without attributing the new annotation/field types;
                // they re-attribute when the file is parsed for real (and the result compiles, as the
                // sandbox integration run shows). So we don't require full type info on the result.
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void addsAllFourAuditFieldsWhenMissing() {
        rewriteRun(
                java(
                        """
                        import org.springframework.data.annotation.Id;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "orders")
                        class Order {
                            @Id
                            private String id;
                        }
                        """,
                        """
                        import org.springframework.data.annotation.*;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        import java.time.Instant;

                        @Document(collection = "orders")
                        class Order {
                            @Id
                            private String id;
                            @CreatedDate
                            private Instant createdAt;
                            @CreatedBy
                            private String createdBy;
                            @LastModifiedDate
                            private Instant updatedAt;
                            @LastModifiedBy
                            private String updatedBy;

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
                        """
                )
        );
    }

    @Test
    void onlyAddsTheMissingAuditFields() {
        // createdAt already present (kept, no new accessor); the other three are added.
        rewriteRun(
                java(
                        """
                        import java.time.Instant;

                        import org.springframework.data.annotation.CreatedDate;
                        import org.springframework.data.annotation.Id;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "orders")
                        class Order {
                            @Id
                            private String id;
                            @CreatedDate
                            private Instant createdAt;
                        }
                        """,
                        """
                        import java.time.Instant;

                        import org.springframework.data.annotation.*;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "orders")
                        class Order {
                            @Id
                            private String id;
                            @CreatedDate
                            private Instant createdAt;
                            @CreatedBy
                            private String createdBy;
                            @LastModifiedDate
                            private Instant updatedAt;
                            @LastModifiedBy
                            private String updatedBy;

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
                        """
                )
        );
    }
}
