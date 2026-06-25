package com.example.versionsandbox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

/**
 * Build-time guard rails (run as a normal unit test — no Mongo needed) that make the cross-cutting
 * persistence fields impossible to forget and the unsafe write paths impossible to reintroduce:
 *
 * <ol>
 *   <li>Every {@code @Document} declares a nullable {@code @Version} and the four audit fields with the
 *       correct Spring Data annotations. A new entity without them fails the build.</li>
 *   <li>{@code findAndReplace} and {@code bulkOps} — the two write paths that silently corrupt/skip the
 *       version and audit fields — may only be called from the one pre-existing, grandfathered class
 *       ({@code MongoTemplateService}). Any new caller fails the build.</li>
 * </ol>
 */
class PersistenceFieldsRulesTest {

    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.versionsandbox");

    /** The one class allowed to keep using the unsafe template writes (legacy; flagged in the report). */
    private static final String GRANDFATHERED_UNSAFE_WRITER =
            "com.example.versionsandbox.service.MongoTemplateService";

    @Test
    void everyDocumentDeclaresVersionAndAuditFields() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(Document.class)
                .should(declareFieldAnnotatedWith(Version.class))
                .andShould(declareFieldAnnotatedWith(CreatedDate.class))
                .andShould(declareFieldAnnotatedWith(CreatedBy.class))
                .andShould(declareFieldAnnotatedWith(LastModifiedDate.class))
                .andShould(declareFieldAnnotatedWith(LastModifiedBy.class))
                .as("every @Document must declare @Version + the four audit fields "
                        + "(@CreatedDate/@CreatedBy/@LastModifiedDate/@LastModifiedBy)");
        rule.check(APP);
    }

    @Test
    void unsafeTemplateWritesAreConfinedToTheGrandfatheredClass() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(GRANDFATHERED_UNSAFE_WRITER)
                .should().callMethodWhere(unsafeMongoWrite())
                .as("findAndReplace / bulkOps drop or skip @Version and reset audit fields; "
                        + "they may only be used in " + GRANDFATHERED_UNSAFE_WRITER + " (grandfathered)");
        rule.check(APP);
    }

    private static DescribedPredicate<JavaMethodCall> unsafeMongoWrite() {
        return new DescribedPredicate<>("findAndReplace or bulkOps on MongoOperations") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getFullName();
                String method = call.getTarget().getName();
                boolean mongoOps = owner.startsWith("org.springframework.data.mongodb.core.MongoOperations")
                        || owner.startsWith("org.springframework.data.mongodb.core.MongoTemplate");
                return mongoOps && (method.equals("findAndReplace") || method.equals("bulkOps"));
            }
        };
    }

    private static ArchCondition<JavaClass> declareFieldAnnotatedWith(Class<? extends Annotation> annotation) {
        return new ArchCondition<>("declare a field annotated with @" + annotation.getSimpleName()) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean present = clazz.getAllFields().stream()
                        .anyMatch((JavaField f) -> f.isAnnotatedWith(annotation));
                events.add(new SimpleConditionEvent(clazz, present,
                        clazz.getName() + (present ? " declares" : " is MISSING")
                                + " a field annotated with @" + annotation.getSimpleName()));
            }
        };
    }
}
