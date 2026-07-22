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
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
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

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.asymetrik.modular.api.Module;

@SupportedAnnotationTypes("org.asymetrik.modular.api.Module")
@SupportedOptions(ModuleAnnotationProcessor.TREE_SCAN_OPTION)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ModuleAnnotationProcessor extends AbstractProcessor {

    /** javac option ({@code -A<key>=false}) to disable Tree API-based scanning (locals, string
     *  literals, imports, wildcard-import detection) when compile time matters more than full
     *  coverage — e.g. disabled for fast local builds, left enabled (default) on CI. The baseline
     *  Element/TypeMirror signature scan is unaffected and always runs. */
    static final String TREE_SCAN_OPTION = "org.asymetrik.modular.treeScan";

    /** Compiler Tree API entry point; only available when running under javac and when not disabled
     *  via {@link #TREE_SCAN_OPTION}. Null-safe: tree-based scanning is simply skipped if unavailable. */
    private Trees trees;

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

    /** A non-static, type-import-on-demand ({@code import some.pkg.*;}) present in a compilation unit. */
    private record WildcardImport(String packageName, ImportTree importTree) {}

    private record TreeScanResult(
        Set<TypeElement> referencedTypes,
        Set<TypeElement> stringLiteralTypes,
        Set<TypeElement> importedTypes,
        List<WildcardImport> wildcardImports,
        CompilationUnitTree compilationUnit
    ) {
        static final TreeScanResult EMPTY = new TreeScanResult(Set.of(), Set.of(), Set.of(), List.of(), null);

        boolean isExplicitlyImported(TypeElement dep) {
            return importedTypes.contains(dep);
        }

        Optional<WildcardImport> wildcardCovering(String packageName) {
            return wildcardImports.stream().filter(w -> w.packageName().equals(packageName)).findFirst();
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment procEnv) {
        super.init(procEnv);

        String option = procEnv.getOptions().get(TREE_SCAN_OPTION);
        boolean treeScanEnabled = option == null || Boolean.parseBoolean(option);
        if (!treeScanEnabled) {
            this.trees = null;
            return;
        }
        try {
            this.trees = Trees.instance(procEnv);
        } catch (IllegalArgumentException notJavac) {
            this.trees = null;
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

            Set<String> reported = new LinkedHashSet<>();
            TreeScanResult treeResult = collectTreeReferencedTypes(typeElement);

            for (TypeElement dep : collectReferencedTypes(typeElement)) {
                reportExportViolationIfAny(typeElement, consumer, dep, modules, reported, null, treeResult);
            }
            for (TypeElement dep : treeResult.importedTypes()) {
                reportExportViolationIfAny(typeElement, consumer, dep, modules, reported, "import declaration", treeResult);
            }
            for (TypeElement dep : treeResult.referencedTypes()) {
                reportExportViolationIfAny(typeElement, consumer, dep, modules, reported,
                    "type/member reference in method body", treeResult);
            }
            for (TypeElement dep : treeResult.stringLiteralTypes()) {
                reportExportViolationIfAny(typeElement, consumer, dep, modules, reported,
                    "string literal reference — heuristic match, verify this is intentional", null);
            }
        }
    }

    private void reportExportViolationIfAny(TypeElement typeElement, ModuleEntry consumer, TypeElement dep,
                                             List<ModuleEntry> modules, Set<String> reported, String detectionNote,
                                             TreeScanResult wildcardContext) {
        String depQualifiedName = dep.getQualifiedName().toString();
        if (!reported.add(depQualifiedName)) return;

        String depPackage = processingEnv.getElementUtils()
            .getPackageOf(dep).getQualifiedName().toString();

        Optional<ModuleEntry> providerOpt = findModuleForPackage(depPackage, modules);
        if (providerOpt.isEmpty()) return;
        ModuleEntry provider = providerOpt.get();
        if (provider.packageName().equals(consumer.packageName())) return;
        if (isPackageExported(depPackage, provider)) return;

        if (wildcardContext != null && !wildcardContext.isExplicitlyImported(dep)) {
            Optional<WildcardImport> wildcardMatch = wildcardContext.wildcardCovering(depPackage);
            if (wildcardMatch.isPresent()) {
                reportWildcardViolation(wildcardMatch.get(), consumer, provider, depQualifiedName, depPackage,
                    wildcardContext.compilationUnit());
                return;
            }
        }

        String message = String.format(
            "Export violation: '%s' (%s) uses '%s' from module '%s' (%s) " +
            "which does not export package '%s'. " +
            "Module '%s' should add '%s' to its exports or '%s' should not use this type.",
            consumer.name(), consumer.packageName(),
            depQualifiedName,
            provider.name(), provider.packageName(),
            depPackage,
            provider.name(), depPackage,
            consumer.name()
        );
        if (detectionNote != null) {
            message += " [detected via: " + detectionNote + "]";
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, typeElement);
    }

    /** Flags the wildcard import itself as the violation, rather than the (indirect) usage site,
     *  since the wildcard is what let a non-exported type resolve by simple name in the first place. */
    private void reportWildcardViolation(WildcardImport wildcard, ModuleEntry consumer, ModuleEntry provider,
                                          String depQualifiedName, String depPackage,
                                          CompilationUnitTree compilationUnit) {
        String message = String.format(
            "Export violation: wildcard import '%s.*' allows '%s' (%s) to use '%s' from module '%s' (%s) " +
            "which does not export package '%s'. " +
            "Replace the wildcard with an explicit import of only exported types, " +
            "or add '%s' to module '%s' exports.",
            wildcard.packageName(),
            consumer.name(), consumer.packageName(),
            depQualifiedName,
            provider.name(), provider.packageName(),
            depPackage,
            depPackage, provider.name()
        );
        trees.printMessage(Diagnostic.Kind.ERROR, message, wildcard.importTree(), compilationUnit);
    }

    /**
     * Scans a class (via the Compiler Tree API) for dependencies invisible to the Element/TypeMirror-only
     * {@link #collectReferencedTypes(TypeElement)}: any identifier or qualified reference resolving to a
     * type or member (local variable declarations, static-imported calls, qualified static access, string
     * literals naming a class, fully-qualified inline usage), plus the file's own import declarations —
     * including flagging which non-exported types are only reachable through a wildcard import.
     */
    private TreeScanResult collectTreeReferencedTypes(TypeElement element) {
        if (trees == null) return TreeScanResult.EMPTY;

        Tree tree = trees.getTree(element);
        if (!(tree instanceof ClassTree classTree)) return TreeScanResult.EMPTY;

        TreePath rootPath = trees.getPath(element);
        if (rootPath == null) return TreeScanResult.EMPTY;

        CompilationUnitTree compilationUnit = rootPath.getCompilationUnit();
        Set<TypeElement> importedTypes = new LinkedHashSet<>();
        Set<String> importedSeen = new LinkedHashSet<>();
        List<WildcardImport> wildcardImports = new ArrayList<>();
        collectImports(compilationUnit, importedTypes, importedSeen, wildcardImports);

        Set<TypeElement> referencedTypes = new LinkedHashSet<>();
        Set<String> referencedSeen = new LinkedHashSet<>();
        Set<TypeElement> stringLiteralTypes = new LinkedHashSet<>();

        TreePathScanner<Void, Void> scanner = new TreePathScanner<>() {
            @Override
            public Void visitClass(ClassTree node, Void p) {
                // Nested/inner types are scanned separately as their own root elements.
                if (node != classTree) return null;
                return super.visitClass(node, p);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void p) {
                resolveOwningType(trees.getElement(getCurrentPath()))
                    .ifPresent(te -> addIfNew(referencedTypes, referencedSeen, te));
                return super.visitIdentifier(node, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void p) {
                resolveOwningType(trees.getElement(getCurrentPath()))
                    .ifPresent(te -> addIfNew(referencedTypes, referencedSeen, te));
                return super.visitMemberSelect(node, p);
            }

            @Override
            public Void visitLiteral(LiteralTree node, Void p) {
                if (node.getKind() == Tree.Kind.STRING_LITERAL && node.getValue() instanceof String literal) {
                    TypeElement resolved = processingEnv.getElementUtils().getTypeElement(literal);
                    if (resolved != null) {
                        stringLiteralTypes.add(resolved);
                    }
                }
                return super.visitLiteral(node, p);
            }
        };

        scanner.scan(rootPath, null);

        return new TreeScanResult(referencedTypes, stringLiteralTypes, importedTypes, wildcardImports, compilationUnit);
    }

    /** Classifies each import: explicit type/static-member imports resolve directly to their type;
     *  a non-static wildcard ({@code import some.pkg.*;}) only names a package, so it is recorded
     *  separately for cross-referencing against types actually found in the body. */
    private void collectImports(CompilationUnitTree compilationUnit, Set<TypeElement> importedTypes,
                                 Set<String> importedSeen, List<WildcardImport> wildcardImports) {
        for (ImportTree imp : compilationUnit.getImports()) {
            Tree qualId = imp.getQualifiedIdentifier();
            String qualifiedText = qualId.toString();
            boolean isWildcard = qualifiedText.endsWith(".*");

            if (imp.isStatic()) {
                if (isWildcard) {
                    String typeName = qualifiedText.substring(0, qualifiedText.length() - 2);
                    TypeElement te = processingEnv.getElementUtils().getTypeElement(typeName);
                    if (te != null) addIfNew(importedTypes, importedSeen, te);
                } else {
                    Element el = trees.getElement(TreePath.getPath(compilationUnit, qualId));
                    resolveOwningType(el).ifPresent(te -> addIfNew(importedTypes, importedSeen, te));
                }
            } else if (isWildcard) {
                String packageName = qualifiedText.substring(0, qualifiedText.length() - 2);
                wildcardImports.add(new WildcardImport(packageName, imp));
            } else {
                Element el = trees.getElement(TreePath.getPath(compilationUnit, qualId));
                if (el instanceof TypeElement te) addIfNew(importedTypes, importedSeen, te);
            }
        }
    }

    /** Resolves a symbol to the class/interface it names, or — for a field/method/constructor —
     *  the type it belongs to (this is what makes an unqualified call reached only via a static
     *  import still resolve to its owning class). Local variables and parameters resolve to nothing
     *  useful here since they don't identify a dependency beyond their already-scanned declaration. */
    private static Optional<TypeElement> resolveOwningType(Element el) {
        if (el == null) return Optional.empty();
        return switch (el.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> Optional.of((TypeElement) el);
            case FIELD, METHOD, CONSTRUCTOR, ENUM_CONSTANT -> {
                Element enclosing = el.getEnclosingElement();
                yield (enclosing instanceof TypeElement te) ? Optional.of(te) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    private static void addIfNew(Set<TypeElement> collected, Set<String> seen, TypeElement te) {
        if (seen.add(te.getQualifiedName().toString())) {
            collected.add(te);
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
