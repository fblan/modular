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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.InternalClass", "InternalClass"
            )
        );
        assertThat(fieldResult).filteredOn(d -> d.getKind() == ERROR).hasSize(1);

        var bodyOnlyResult = compile(disableTreeScan,
            packageInfo("com.example.producer", "producer", "com.example.producer.exported"),
            classSource("com.example.producer.InternalClass", "com.example.producer", "InternalClass"),
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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
            packageInfoRequiring("com.example.consumer", "consumer", List.of("producer"), "."),
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

    @Test
    void usingClassOfModuleNotRequired_producesRequiresViolationError() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "."),
            classSource("com.example.producer.ProducerClass", "com.example.producer", "ProducerClass"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.producer.ProducerClass", "ProducerClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requires violation")
            .contains("'consumer'")
            .contains("'producer'")
            .contains("Add \"producer\" to the requires of module 'consumer'");
    }

    @Test
    void usingNonExportedClassOfModuleNotRequired_producesOnlyRequiresViolationError() {
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
            .contains("Requires violation");
    }

    @Test
    void requiresIsNotTransitive_producesRequiresViolationError() {
        var result = compile(
            packageInfo("com.example.base", "base", "."),
            classSource("com.example.base.BaseClass", "com.example.base", "BaseClass"),
            packageInfoRequiring("com.example.middle", "middle", List.of("base"), "."),
            classSourceWithField(
                "com.example.middle.MiddleClass", "com.example.middle", "MiddleClass",
                "com.example.base.BaseClass", "BaseClass"
            ),
            packageInfoRequiring("com.example.top", "top", List.of("middle"), "."),
            classSourceWithField(
                "com.example.top.TopClass", "com.example.top", "TopClass",
                "com.example.base.BaseClass", "BaseClass"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requires violation")
            .contains("'top'")
            .contains("'base'");
    }

    @Test
    void severalTypesFromModuleNotRequired_produceSingleRequiresViolation() {
        var result = compile(
            packageInfo("com.example.producer", "producer", "."),
            classSource("com.example.producer.First", "com.example.producer", "First"),
            classSource("com.example.producer.Second", "com.example.producer", "Second"),
            packageInfo("com.example.consumer", "consumer", "."),
            inMemorySource("com.example.consumer.ConsumerClass",
                "package com.example.consumer;\n" +
                "public class ConsumerClass { com.example.producer.First a; com.example.producer.Second b; }\n")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).hasSize(1);
    }

    @Test
    void duplicateModuleNames_producesDuplicateModuleNameError() {
        var result = compile(
            packageInfo("com.example.first", "same", "."),
            classSource("com.example.first.FirstClass", "com.example.first", "FirstClass"),
            packageInfo("com.example.second", "same", "."),
            classSource("com.example.second.SecondClass", "com.example.second", "SecondClass")
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Duplicate module name")
            .contains("'same'")
            .contains("com.example.first")
            .contains("com.example.second");
    }

    @Test
    void requiringModuleNotInCompilation_producesNoErrors() {
        // Could be a module compiled earlier (incremental build): only ModularVerifier reports unknown names.
        var result = compile(
            packageInfoRequiring("com.example.consumer", "consumer", List.of("elsewhere"), "."),
            classSource("com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingJavaClasses_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.consumer", "consumer", "."),
            inMemorySource("com.example.consumer.ConsumerClass",
                "package com.example.consumer;\n" +
                "public class ConsumerClass { java.util.List<String> names; java.time.Instant at; }\n")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingArrayLengthAndClone_producesNoErrors() {
        var result = compile(
            packageInfo("com.example.consumer", "consumer", "."),
            inMemorySource("com.example.consumer.ConsumerClass",
                "package com.example.consumer;\n" +
                "public class ConsumerClass { int run(String[] args) { return args.length + args.clone().length; } }\n")
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingJdkClassOfJpmsModuleNotRequired_producesRequiresViolationError() {
        var result = compile(
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "javax.annotation.processing.Processor", "Processor"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requires violation")
            .contains("JPMS module 'java.compiler'")
            .contains("Add \"java.compiler\" to the requires of module 'consumer'");
    }

    @Test
    void usingJdkClassOfRequiredJpmsModule_producesNoErrors() {
        var result = compile(
            packageInfoRequiring("com.example.consumer", "consumer", List.of("java.compiler"), "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "javax.annotation.processing.Processor", "Processor"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingClassPathJarClassOfJpmsModuleNotRequired_producesRequiresViolationError() {
        // junit-jupiter-api is on the test class path, and its module-info names it org.junit.jupiter.api
        var result = compile(
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "org.junit.jupiter.api.TestInfo", "TestInfo"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requires violation")
            .contains("JPMS module 'org.junit.jupiter.api'");
    }

    @Test
    void usingClassPathJarClassOfRequiredJpmsModule_producesNoErrors() {
        var result = compile(
            packageInfoRequiring("com.example.consumer", "consumer", List.of("org.junit.jupiter.api"), "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "org.junit.jupiter.api.TestInfo", "TestInfo"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingOwnPackageWithoutModule_producesUnmanagedDependencyError() {
        var result = compile(
            classSource("com.example.util.Helper", "com.example.util", "Helper"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "com.example.util.Helper", "Helper"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Unmanaged dependency")
            .contains("com.example.util.Helper");
    }

    @Test
    void usingClassOfRequiredExternalModule_inSubPackage_producesNoErrors() {
        var result = compile(
            externalModuleInfo("com.example.external", "annotation", "javax.annotation"),
            packageInfoRequiring("com.example.consumer", "consumer", List.of("annotation"), "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "javax.annotation.processing.Processor", "Processor"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void usingClassOfExternalModuleNotRequired_producesRequiresViolationError() {
        var result = compile(
            externalModuleInfo("com.example.external", "annotation", "javax.annotation"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "javax.annotation.processing.Processor", "Processor"
            )
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requires violation")
            .contains("external module 'annotation'")
            .contains("Add \"annotation\" to the requires of module 'consumer'");
    }

    @Test
    void repeatedExternalModules_mostSpecificPackageWins() {
        var result = compile(
            inMemorySource("com.example.external.package-info",
                "@ExternalModule(name = \"annotation\", packages = {\"javax.annotation\"})\n" +
                "@ExternalModule(name = \"processing\", packages = {\"javax.annotation.processing\"})\n" +
                "package com.example.external;\n" +
                "import org.asymetrik.modular.annotations.ExternalModule;\n"),
            packageInfoRequiring("com.example.consumer", "consumer", List.of("processing"), "."),
            classSourceWithField(
                "com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass",
                "javax.annotation.processing.Processor", "Processor"
            )
        );

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void externalModuleWithSameNameAsModule_producesDuplicateModuleNameError() {
        var result = compile(
            externalModuleInfo("com.example.external", "consumer", "javax.annotation"),
            packageInfo("com.example.consumer", "consumer", "."),
            classSource("com.example.consumer.ConsumerClass", "com.example.consumer", "ConsumerClass")
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Duplicate module name")
            .contains("'consumer'")
            .contains("@ExternalModule on com.example.external");
    }

    // --- helpers ---

    private static JavaFileObject externalModuleInfo(String pkg, String moduleName, String... packages) {
        String source = String.format(
            "@ExternalModule(name = \"%s\", packages = {%s})%n" +
            "package %s;%n" +
            "import org.asymetrik.modular.annotations.ExternalModule;%n",
            moduleName, quotedList(List.of(packages)), pkg
        );
        return inMemorySource(pkg + ".package-info", source);
    }

    private static JavaFileObject packageInfo(String pkg, String moduleName, String... exports) {
        return packageInfoRequiring(pkg, moduleName, List.of(), exports);
    }

    private static JavaFileObject packageInfoRequiring(String pkg, String moduleName, List<String> requires,
                                                       String... exports) {
        String source = String.format(
            "@Module(name = \"%s\", exports = {%s}, requires = {%s})%n" +
            "package %s;%n" +
            "import org.asymetrik.modular.annotations.Module;%n",
            moduleName, quotedList(List.of(exports)), quotedList(requires), pkg
        );
        return inMemorySource(pkg + ".package-info", source);
    }

    private static String quotedList(List<String> values) {
        return String.join(", ", values.stream().map(v -> "\"" + v + "\"").toList());
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