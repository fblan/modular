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
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, null)) {
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "--release", "21",
                "-proc:only"
            );
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