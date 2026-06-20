/*
 * Copyright 2026 Asymetryk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asymetrik.modular.processor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor14;
import javax.tools.Diagnostic;

import org.asymetrik.modular.api.Module;

@SupportedAnnotationTypes("org.asymetrik.modular.api.Module")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ModuleAnnotationProcessor extends AbstractProcessor {

    private record ModuleEntry(String name, String packageName, List<String> exports, Element element) {
        boolean isDescendantOf(ModuleEntry other) {
            return this.packageName.startsWith(other.packageName + ".");
        }

        int nestingLevelFrom(ModuleEntry parent) {
            if (!isDescendantOf(parent)) return 0;
            String relative = this.packageName.substring(parent.packageName.length() + 1);
            return relative.split("\\.").length;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        List<ModuleEntry> modules = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Module.class)) {
            if (element.getKind() == ElementKind.PACKAGE) {
                PackageElement pkg = (PackageElement) element;
                Module annotation = pkg.getAnnotation(Module.class);
                modules.add(new ModuleEntry(
                    annotation.name(),
                    pkg.getQualifiedName().toString(),
                    List.of(annotation.exports()),
                    element
                ));
            }
        }

        checkHierarchyViolations(modules);
        checkExportViolations(roundEnv, modules);

        return true;
    }

    private void checkHierarchyViolations(List<ModuleEntry> modules) {
        for (ModuleEntry parent : modules) {
            for (ModuleEntry candidate : modules) {
                if (candidate.isDescendantOf(parent)) {
                    int level = candidate.nestingLevelFrom(parent);
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        String.format(
                            "Module hierarchy violation: '%s' (%s) is nested inside '%s' (%s) at level %d (%s). " +
                            "Modules must not be nested — move '%s' to a sibling package of '%s'.",
                            candidate.name(), candidate.packageName(),
                            parent.name(), parent.packageName(),
                            level, level == 1 ? "direct child" : "indirect descendant",
                            candidate.name(), parent.packageName()
                        ),
                        candidate.element()
                    );
                }
            }
        }
    }

    private void checkExportViolations(RoundEnvironment roundEnv, List<ModuleEntry> modules) {
        for (Element rootElement : roundEnv.getRootElements()) {
            if (!(rootElement instanceof TypeElement typeElement)) continue;
            if (typeElement.getSimpleName().contentEquals("package-info")) continue;

            String classPackage = processingEnv.getElementUtils()
                .getPackageOf(typeElement).getQualifiedName().toString();

            Optional<ModuleEntry> consumerOpt = findModuleForPackage(classPackage, modules);
            if (consumerOpt.isEmpty()) continue;
            ModuleEntry consumer = consumerOpt.get();

            for (TypeElement dep : collectReferencedTypes(typeElement)) {
                String depPackage = processingEnv.getElementUtils()
                    .getPackageOf(dep).getQualifiedName().toString();

                Optional<ModuleEntry> providerOpt = findModuleForPackage(depPackage, modules);
                if (providerOpt.isEmpty()) continue;
                ModuleEntry provider = providerOpt.get();
                if (provider.packageName().equals(consumer.packageName())) continue;

                if (!isPackageExported(depPackage, provider)) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        String.format(
                            "Export violation: '%s' (%s) uses '%s' from module '%s' (%s) " +
                            "which does not export package '%s'. " +
                            "Module '%s' should add '%s' to its exports or '%s' should not use this type.",
                            consumer.name(), consumer.packageName(),
                            dep.getQualifiedName(),
                            provider.name(), provider.packageName(),
                            depPackage,
                            provider.name(), depPackage,
                            consumer.name()
                        ),
                        typeElement
                    );
                }
            }
        }
    }

    private Set<TypeElement> collectReferencedTypes(TypeElement element) {
        Set<TypeElement> result = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        TypeMirrorCollector collector = new TypeMirrorCollector(result, seen);

        collector.visit(element.getSuperclass());
        element.getInterfaces().forEach(collector::visit);

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof VariableElement field) {
                collector.visit(field.asType());
            } else if (enclosed instanceof ExecutableElement method) {
                collector.visit(method.getReturnType());
                method.getParameters().forEach(p -> collector.visit(p.asType()));
                method.getThrownTypes().forEach(collector::visit);
            }
        }

        return result;
    }

    private Optional<ModuleEntry> findModuleForPackage(String packageName, List<ModuleEntry> modules) {
        return modules.stream()
            .filter(m -> packageName.equals(m.packageName()) || packageName.startsWith(m.packageName() + "."))
            .reduce((m1, m2) -> m1.packageName().length() > m2.packageName().length() ? m1 : m2);
    }

    private boolean isPackageExported(String packageName, ModuleEntry module) {
        for (String export : module.exports()) {
            if (export.equals(packageName)) return true;
            if (export.equals(".") && packageName.equals(module.packageName())) return true;
            if (export.endsWith("*") && packageName.startsWith(export.substring(0, export.length() - 1))) return true;
        }
        return false;
    }

    private static class TypeMirrorCollector extends SimpleTypeVisitor14<Void, Void> {
        private final Set<TypeElement> collected;
        private final Set<String> seen;

        TypeMirrorCollector(Set<TypeElement> collected, Set<String> seen) {
            super(null);
            this.collected = collected;
            this.seen = seen;
        }

        @Override
        public Void visitDeclared(DeclaredType t, Void unused) {
            Element e = t.asElement();
            if (e instanceof TypeElement te && seen.add(te.getQualifiedName().toString())) {
                collected.add(te);
            }
            t.getTypeArguments().forEach(arg -> visit(arg, null));
            return null;
        }

        @Override
        public Void visitArray(ArrayType t, Void unused) {
            return visit(t.getComponentType(), null);
        }

        @Override
        public Void visitWildcard(WildcardType t, Void unused) {
            if (t.getExtendsBound() != null) visit(t.getExtendsBound(), null);
            if (t.getSuperBound() != null) visit(t.getSuperBound(), null);
            return null;
        }
    }
}
