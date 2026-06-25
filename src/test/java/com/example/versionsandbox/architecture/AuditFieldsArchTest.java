package com.example.versionsandbox.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

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
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Structural enforcement (path 1 of the audit problem, see {@code AUDIT.md} on {@code main}):
 * every persisted entity ({@code @Document}) must declare all four audit fields with the correct
 * Spring Data annotations. ArchUnit catches the "some classes don't have them all" case at build
 * time — but note what it <em>cannot</em> do: verify that an arbitrary {@code MongoTemplate} update
 * actually <em>sets</em> the fields at runtime. That behavioural guarantee is the AOP branch's job;
 * this is the structural half of the recommended hybrid.
 */
class AuditFieldsArchTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.versionsandbox");

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
}
