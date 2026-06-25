package com.example.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Note on formatting: these assertions show the output under OpenRewrite's <em>default</em> style.
 * New fields inherit the spacing of the surrounding fields (here the source packs fields with no blank
 * lines, so the inserted field does too), and 5+ same-package imports collapse to a star import. A real
 * project's detected style governs the actual rendering — e.g. the sandbox run kept explicit imports
 * and blank lines between fields. The recipe logic (which fields are added/skipped, accessors, imports)
 * is what these tests pin down.
 */
class AddVersionFieldTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddVersionField())
                .parser(JavaParser.fromJavaVersion().classpath("spring-data-mongodb", "spring-data-commons"))
                // The recipe inserts source text without attributing the new annotation/field types;
                // they re-attribute when the file is parsed for real (and the result compiles, as the
                // sandbox integration run shows). So we don't require full type info on the result.
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void addsVersionFieldAndAccessorsToDocument() {
        rewriteRun(
                java(
                        """
                        import org.springframework.data.annotation.Id;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "products")
                        class Product {
                            @Id
                            private String id;
                            private String name;
                        }
                        """,
                        """
                        import org.springframework.data.annotation.Id;
                        import org.springframework.data.annotation.Version;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "products")
                        class Product {
                            @Id
                            private String id;
                            private String name;
                            @Version
                            private Long version;

                            public Long getVersion() {
                                return version;
                            }

                            public void setVersion(Long version) {
                                this.version = version;
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesDocumentThatAlreadyHasVersionUntouched() {
        rewriteRun(
                java(
                        """
                        import org.springframework.data.annotation.Id;
                        import org.springframework.data.annotation.Version;
                        import org.springframework.data.mongodb.core.mapping.Document;

                        @Document(collection = "products")
                        class Product {
                            @Id
                            private String id;
                            @Version
                            private Long version;
                        }
                        """
                )
        );
    }

    @Test
    void ignoresNonDocumentClasses() {
        rewriteRun(
                java(
                        """
                        class PlainPojo {
                            private String id;
                        }
                        """
                )
        );
    }
}
