package com.example.versionsandbox.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Structural half of the guarantee: ArchUnit proves the five cross-cutting fields are <em>declared</em>
 * (with the right annotations) on every persisted entity, and that the two unsafe write paths aren't
 * newly introduced. It cannot prove the fields are <em>filled</em> at runtime — that's the aspect's +
 * behavioural test's job ({@code AuditVersionMaintainedTest}). You need both.
 *
 * <p>Every {@code @Document} in this app ({@code Product}, {@code Order}) is versioned + audited, so the
 * version rule applies to all {@code @Document} classes with no scoping.
 */
class AuditAndVersionArchTest {

    private static final String IMPORT_ROOT = "com.example.versionsandbox";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(IMPORT_ROOT);

    @Test
    void everyDocumentDeclaresAllFourAuditFields() {
        Map<String, Class<? extends Annotation>> required = new LinkedHashMap<>();
        required.put("createdAt", CreatedDate.class);
        required.put("createdBy", CreatedBy.class);
        required.put("updatedAt", LastModifiedDate.class);
        required.put("updatedBy", LastModifiedBy.class);

        ArchRule rule = classes()
                .that().areAnnotatedWith(Document.class)
                .should(new ArchCondition<>("declare createdAt/By + updatedAt/By with the audit annotations") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        required.forEach((field, annotation) -> {
                            var declared = clazz.tryGetField(field);
                            if (declared.isEmpty()) {
                                events.add(SimpleConditionEvent.violated(clazz,
                                        clazz.getName() + " is missing audit field '" + field + "'"));
                            } else if (!declared.get().isAnnotatedWith(annotation)) {
                                events.add(SimpleConditionEvent.violated(clazz,
                                        clazz.getName() + "." + field + " must be annotated @" + annotation.getSimpleName()));
                            }
                        });
                    }
                });

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void everyDocumentDeclaresVersion() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(Document.class)
                .should(new ArchCondition<>("declare a 'version' field annotated @Version") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        var declared = clazz.tryGetField("version");
                        if (declared.isEmpty()) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getName() + " is missing the 'version' field"));
                        } else if (!declared.get().isAnnotatedWith(Version.class)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getName() + ".version must be annotated @Version"));
                        }
                    }
                });

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Keep the two write paths that don't maintain audit/version from spreading. {@code findAndReplace}
     * resets created fields and drops the version; {@code bulkOps} skips both. This app already has two
     * legitimate call sites in {@code MongoTemplateService} ({@code findAndReplaceById},
     * {@code bulkMixedWrite}); they are grandfathered via {@link FreezingArchRule} (the frozen store
     * lives under {@code src/test/resources/frozen}). Any NEW call site fails the build until the store
     * is intentionally updated. The runtime aspects WARN on these same paths.
     */
    @Test
    void noNewCodeUsesFindAndReplaceOrBulkOps() {
        ArchRule rule = noClasses()
                .should().callMethodWhere(
                        com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                com.tngtech.archunit.base.DescribedPredicate.describe(
                                        "MongoOperations.findAndReplace(..) or bulkOps(..)",
                                        target -> {
                                            String name = target.getName();
                                            if (!name.equals("findAndReplace") && !name.equals("bulkOps")) {
                                                return false;
                                            }
                                            // Match the call whether it is made through the MongoOperations
                                            // interface OR the concrete MongoTemplate (which implements it).
                                            // App code declares `MongoTemplate mongoTemplate`, so the call
                                            // target's owner is MongoTemplate, not the interface — matching
                                            // only the interface name would silently catch nothing.
                                            JavaClass owner = target.getOwner();
                                            return owner.isAssignableTo("org.springframework.data.mongodb.core.MongoOperations")
                                                    || owner.getName().equals("org.springframework.data.mongodb.core.MongoOperations");
                                        })))
                .because("findAndReplace resets created fields / drops @Version; bulkOps maintains neither — "
                        + "prefer save()/findAndModify, or add .inc(\"version\",1) and the audit fields explicitly");

        FreezingArchRule.freeze(rule).check(PRODUCTION_CLASSES);
    }
}
