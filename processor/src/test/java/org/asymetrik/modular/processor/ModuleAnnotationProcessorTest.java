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

import java.net.URI;
import java.util.List;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import static javax.tools.Diagnostic.Kind.ERROR;
import static org.assertj.core.api.Assertions.assertThat;

class ModuleAnnotationProcessorTest {

    @Test
    void validIsolatedModule_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.mymodule", "mymodule", "."),
            classSource("com.example.mymodule.MyClass", "com.example.mymodule", "MyClass")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void noModuleAnnotation_producesNoErrors() {
        var result = compile(
            classSource("com.example.plain.Plain", "com.example.plain", "Plain")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void nestedModules_directChild_producesHierarchyViolationError() {
        var result = compile(
            packageInfo("com.example.parent", "parent", "."),
            classSource("com.example.parent.ParentClass", "com.example.parent", "ParentClass"),
            packageInfo("com.example.parent.child", "child", "."),
            classSource("com.example.parent.child.ChildClass", "com.example.parent.child", "ChildClass")
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Module hierarchy violation")
            .contains("'child'")
            .contains("'parent'")
            .contains("direct child");
    }

    @Test
    void nestedModules_indirectDescendant_producesHierarchyViolationError() {
        var result = compile(
            packageInfo("com.example.root", "root", "."),
            classSource("com.example.root.RootClass", "com.example.root", "RootClass"),
            packageInfo("com.example.root.level1.level2", "deep", "."),
            classSource("com.example.root.level1.level2.DeepClass", "com.example.root.level1.level2", "DeepClass")
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Module hierarchy violation")
            .contains("indirect descendant");
    }

    @Test
    void siblingModules_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.moduleA", "moduleA", "."),
            classSource("com.example.moduleA.ClassA", "com.example.moduleA", "ClassA"),
            packageInfo("com.example.moduleB", "moduleB", "."),
            classSource("com.example.moduleB.ClassB", "com.example.moduleB", "ClassB")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingExportedClass_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.exported.ExportedClass", "com.example.producer.exported", "ExportedClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.exported.ExportedClass", "ExportedClass"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingNonExportedClass_producesExportViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass", "InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Export violation")
            .contains("'consumer'")
            .contains("'producer'")
            .contains("com.example.producer");
    }

    @Test
    void fullyQualifiedLocalVariableWithoutImport_producesExportViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithFullyQualifiedLocalVariable(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Export violation")
            .contains("com.example.producer.InternalClass")
            .contains("type/member reference in method body");
    }

    @Test
    void explicitlyImportedNonExportedType_producesExportViolationErrorEvenWithoutUsage() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithUnusedImport(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Export violation")
            .contains("com.example.producer.InternalClass")
            .contains("import declaration");
    }

    @Test
    void staticImportedCallWithoutQualifierOrLocalVariable_producesExportViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSourceWithStaticMethod("com.example.producer.InternalClass", "com.example.producer", "InternalClass", "doThing"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithStaticImportCall(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass", "doThing"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Export violation")
            .contains("com.example.producer.InternalClass");
    }

    @Test
    void wildcardImportAllowingNonExportedType_isFlaggedOnTheImportItself() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithWildcardImportAndLocalVariable(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer", "InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("wildcard import")
            .contains("com.example.producer.*")
            .contains("com.example.producer.InternalClass");
    }

    @Test
    void treeScanDisabled_stillDetectsSignatureViolation_butNotBodyOnlyViolation() {
        List<String> disableTreeScan = List.of("-A" + "org.asymetrik.modular.treeScan=false");

        var fieldResult = compile(disableTreeScan,
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass", "InternalClass"
            )
        );
        assertThat(fieldResult).filteredOn(d -> d.getKind() == ERROR).hasSize(1);

        var bodyOnlyResult = compile(disableTreeScan,
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithFullyQualifiedLocalVariable(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass"
            )
        );
        assertThat(bodyOnlyResult).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void stringLiteralMatchingNonExportedType_producesExportViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithStringLiteral(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Export violation")
            .contains("com.example.producer.InternalClass")
            .contains("string literal reference");
    }

    @Test
    void unrelatedStringLiteral_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithStringLiteral(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "some/random/path"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void localVariableReferencingExportedType_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.exported.ExportedClass", "com.example.producer.exported", "ExportedClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithLocalVariable(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.exported.ExportedClass", "ExportedClass"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void genericLocalVariableTypeArgumentReferencingNonExportedType_producesExportViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithGenericLocalVariable(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("com.example.producer.InternalClass");
    }

    // --- helpers ---

    private static JavaFileObject packageInfo(String pkg, String moduleName, String... exports) {
        String exportsLiteral = String.join(", ", java.util.Arrays.stream(exports)
            .map(e -> "\"" + e + "\"")
            .toArray(String[]::new));
        String source = String.format(
            "@Module(name = \"%s\", exports = {%s})%n" +
            "package %s;%n" +
            "import org.asymetrik.modular.api.Module;%n",
            moduleName, exportsLiteral, pkg
        );
        return inMemorySource(pkg + ".package-info", source);
    }

    private static JavaFileObject classSource(String fqn, String pkg, String simpleName) {
        String source = String.format("package %s;%npublic class %s {}%n", pkg, simpleName);
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithField(String fqn, String pkg, String simpleName,
                                                       String fieldTypeFqn, String fieldTypeSimpleName) {
        String source = String.format(
            "package %s;%nimport %s;%npublic class %s { %s dep; }%n",
            pkg, fieldTypeFqn, simpleName, fieldTypeSimpleName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithLocalVariable(String fqn, String pkg, String simpleName,
                                                                String depTypeFqn, String depTypeSimpleName) {
        String source = String.format(
            "package %s;%nimport %s;%npublic class %s { void run() { %s dep = new %s(); } }%n",
            pkg, depTypeFqn, simpleName, depTypeSimpleName, depTypeSimpleName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithGenericLocalVariable(String fqn, String pkg, String simpleName,
                                                                       String depTypeFqn) {
        String source = String.format(
            "package %s;%nimport java.util.ArrayList;%nimport java.util.List;%n" +
            "public class %s { void run() { List<%s> deps = new ArrayList<>(); } }%n",
            pkg, simpleName, depTypeFqn
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithStringLiteral(String fqn, String pkg, String simpleName,
                                                                String literal) {
        String source = String.format(
            "package %s;%npublic class %s { void run() { String name = \"%s\"; } }%n",
            pkg, simpleName, literal
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithFullyQualifiedLocalVariable(String fqn, String pkg, String simpleName,
                                                                              String depTypeFqn) {
        String source = String.format(
            "package %s;%npublic class %s { void run() { %s dep = new %s(); } }%n",
            pkg, simpleName, depTypeFqn, depTypeFqn
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithUnusedImport(String fqn, String pkg, String simpleName,
                                                               String depTypeFqn) {
        String source = String.format(
            "package %s;%nimport %s;%npublic class %s { }%n",
            pkg, depTypeFqn, simpleName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithStaticMethod(String fqn, String pkg, String simpleName,
                                                               String methodName) {
        String source = String.format(
            "package %s;%npublic class %s { public static void %s() { } }%n",
            pkg, simpleName, methodName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithStaticImportCall(String fqn, String pkg, String simpleName,
                                                                   String depTypeFqn, String methodName) {
        String source = String.format(
            "package %s;%nimport static %s.%s;%npublic class %s { void run() { %s(); } }%n",
            pkg, depTypeFqn, methodName, simpleName, methodName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject classSourceWithWildcardImportAndLocalVariable(String fqn, String pkg, String simpleName,
                                                                                 String depPkg, String depTypeSimpleName) {
        String source = String.format(
            "package %s;%nimport %s.*;%npublic class %s { void run() { %s dep = new %s(); } }%n",
            pkg, depPkg, simpleName, depTypeSimpleName, depTypeSimpleName
        );
        return inMemorySource(fqn, source);
    }

    private static JavaFileObject inMemorySource(String fqn, String source) {
        URI uri = URI.create("string:///" + fqn.replace('.', '/') + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(JavaFileObject... sources) {
        return compile(List.of(), sources);
    }

    private List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(List<String> extraOptions, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, null)) {
            List<String> options = new java.util.ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "--release", "21",
                "-proc:only"
            ));
            options.addAll(extraOptions);
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, collector, options, null, List.of(sources)
            );
            task.setProcessors(List.of(new ModuleAnnotationProcessor()));
            task.call();
        } catch (Exception e) {
            throw new RuntimeException("Compilation failed unexpectedly", e);
        }
        return collector.getDiagnostics();
    }
}