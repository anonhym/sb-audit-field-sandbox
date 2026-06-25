// TEMPLATE — copy into your project's test sources and adjust the package + the importPackages root.
// Needs ArchUnit: com.tngtech.archunit:archunit-junit5 (test scope).
package com.yourorg.architecture;

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
 * Structural half of the guarantee: ArchUnit proves the fields are <em>declared</em> (with the right
 * annotations) on every persisted entity, and that the two unsafe write paths aren't used. It cannot
 * prove the fields are <em>filled</em> at runtime — that's the aspect's + behavioural test's job. You
 * need both.
 *
 * <p>Adjust {@code IMPORT_ROOT} to your base package. If versioning is per-collection rather than
 * universal, scope {@code everyDocumentDeclaresVersion} to just the versioned entities (e.g. select by
 * a marker interface/annotation or a sub-package) instead of all {@code @Document} classes.
 */
class AuditAndVersionArchTest {

    private static final String IMPORT_ROOT = "com.yourorg";

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
     * Keep NEW uses of the two write paths that don't maintain audit/version out of the codebase.
     * {@code findAndReplace} resets created fields and drops the version; {@code bulkOps} skips both.
     *
     * <p>Wrapped in {@link FreezingArchRule} so existing legitimate call sites are grandfathered (the
     * first run records them in a violation store) and only NEW ones fail. Enable the store with an
     * {@code src/test/resources/archunit.properties} (see the {@code archunit.properties} template):
     * <pre>
     *   freeze.store.default.allowStoreCreation=true
     *   freeze.store.default.path=src/test/resources/frozen
     * </pre>
     * then commit the generated {@code frozen/} store.
     *
     * <p><strong>Owner gotcha:</strong> match owners <em>assignable to</em> {@code MongoOperations}, not
     * the interface exactly. A call through a {@code MongoTemplate mongoTemplate} field has owner
     * {@code MongoTemplate}; matching {@code MongoOperations} exactly would silently catch nothing.
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
                                            JavaClass owner = target.getOwner();
                                            return owner.isAssignableTo("org.springframework.data.mongodb.core.MongoOperations")
                                                    || owner.getName().equals("org.springframework.data.mongodb.core.MongoOperations");
                                        })))
                .because("findAndReplace resets created fields / drops @Version; bulkOps maintains neither — "
                        + "prefer save()/findAndModify, or add .inc(\"version\",1) and the audit fields explicitly");

        FreezingArchRule.freeze(rule).check(PRODUCTION_CLASSES);
    }
}
