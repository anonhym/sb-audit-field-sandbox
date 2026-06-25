package com.example.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaCoordinates;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Shared machinery for the two field-adding recipes. For every {@code @Document} class it makes sure
 * each {@link FieldSpec} this recipe declares is present, adding the field (with its annotation and
 * imports) and a getter/setter when they are missing. Anything already present is left exactly as it
 * was — the recipe is idempotent and only ever adds what a developer would otherwise add by hand.
 *
 * <p>This is the mechanical, scales-with-entity-count part of the refactor — precisely what an
 * automated recipe is good at. The behavioural and structural guarantees (auditing config, the AOP
 * aspect, the ArchUnit rules, the optimistic-locking migration strategy) are deliberately <em>not</em>
 * here; they are one-time wiring the skill adds from templates.
 */
abstract class EnsureFieldsRecipe extends Recipe {

    static final String DOCUMENT_FQN = "org.springframework.data.mongodb.core.mapping.Document";

    /** A field this recipe guarantees on every {@code @Document} class. */
    record FieldSpec(String name, String type, String typeImport,
                     String annotationFqn, String annotationSimpleName) {
    }

    /** The fields a concrete recipe ensures. */
    abstract List<FieldSpec> fields();

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getKind() != J.ClassDeclaration.Kind.Type.Class || !isDocument(cd)) {
                    return cd;
                }
                boolean lombokAccessors = hasLombokAccessors(cd);
                for (FieldSpec spec : fields()) {
                    if (hasField(cd, spec.name())) {
                        continue;
                    }
                    cd = addField(cd, spec);
                    if (!lombokAccessors) {
                        cd = addAccessors(cd, spec);
                    }
                }
                return cd;
            }

            private J.ClassDeclaration addField(J.ClassDeclaration cd, FieldSpec spec) {
                // No .javaParser(...classpath) here on purpose: when this recipe runs under the
                // rewrite-maven-plugin, the template parser's classpath is the plugin's launcher
                // classpath (not the target's deps), so resolving "spring-data-commons" throws. The
                // snippet parses fine without type attribution — we emit correct source text and add
                // the imports ourselves (onlyIfReferenced=false, since the freshly inserted annotation
                // may not be type-attributed yet).
                JavaTemplate.Builder builder = JavaTemplate.builder(
                                "@" + spec.annotationSimpleName() + "\nprivate " + spec.type() + " " + spec.name() + ";")
                        .imports(spec.annotationFqn());
                if (spec.typeImport() != null) {
                    builder = builder.imports(spec.typeImport());
                }
                // Insert just after the last existing field so @Id and the entity's own fields stay
                // at the top and the new fields append in declaration order — a clean, reviewable diff.
                // Fall back to the top of the body when the class has no fields yet.
                cd = builder.build().apply(updateCursor(cd), fieldInsertionPoint(cd));
                maybeAddImport(spec.annotationFqn(), false);
                if (spec.typeImport() != null) {
                    maybeAddImport(spec.typeImport(), false);
                }
                return cd;
            }

            private J.ClassDeclaration addAccessors(J.ClassDeclaration cd, FieldSpec spec) {
                String cap = Character.toUpperCase(spec.name().charAt(0)) + spec.name().substring(1);
                String getter = "get" + cap;
                String setter = "set" + cap;
                if (!hasMethod(cd, getter)) {
                    cd = JavaTemplate.builder(
                                    "public " + spec.type() + " " + getter + "() { return " + spec.name() + "; }")
                            .build()
                            .apply(updateCursor(cd), cd.getBody().getCoordinates().lastStatement());
                }
                if (!hasMethod(cd, setter)) {
                    cd = JavaTemplate.builder(
                                    "public void " + setter + "(" + spec.type() + " " + spec.name() + ") { this."
                                            + spec.name() + " = " + spec.name() + "; }")
                            .build()
                            .apply(updateCursor(cd), cd.getBody().getCoordinates().lastStatement());
                }
                return cd;
            }
        };
    }

    /** After the last existing field if there is one, else at the top of the class body. */
    private static JavaCoordinates fieldInsertionPoint(J.ClassDeclaration cd) {
        J.VariableDeclarations lastField = null;
        for (Statement s : cd.getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                lastField = vd;
            }
        }
        return lastField != null
                ? lastField.getCoordinates().after()
                : cd.getBody().getCoordinates().firstStatement();
    }

    private static boolean isDocument(J.ClassDeclaration cd) {
        for (J.Annotation a : cd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(a.getType(), DOCUMENT_FQN)) {
                return true;
            }
            // Fallback when type attribution is incomplete (e.g. the snippet was parsed without the
            // Spring Data jars): match on the simple name.
            if (a.getType() == null && "Document".equals(a.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasField(J.ClassDeclaration cd, String name) {
        return cd.getBody().getStatements().stream()
                .filter(J.VariableDeclarations.class::isInstance)
                .map(J.VariableDeclarations.class::cast)
                .flatMap(v -> v.getVariables().stream())
                .anyMatch(nv -> nv.getSimpleName().equals(name));
    }

    private static boolean hasMethod(J.ClassDeclaration cd, String methodName) {
        return cd.getBody().getStatements().stream()
                .filter(J.MethodDeclaration.class::isInstance)
                .map(J.MethodDeclaration.class::cast)
                .anyMatch(m -> m.getSimpleName().equals(methodName));
    }

    private static boolean hasLombokAccessors(J.ClassDeclaration cd) {
        for (J.Annotation a : cd.getLeadingAnnotations()) {
            String n = a.getSimpleName();
            if ("Data".equals(n) || "Getter".equals(n) || "Setter".equals(n) || "Value".equals(n)) {
                return true;
            }
        }
        return false;
    }
}
