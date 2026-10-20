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
package org.asymetrik.modular.specification.processor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

class RequirementProcessorTest {

    /** Class output of the compilations: where the processor writes the requirement index. */
    @TempDir
    Path out;

    /** Class output of a second compilation, the tests, which reads the index from {@link #out}. */
    @TempDir
    Path testOut;

    @ParameterizedTest
    @ValueSource(strings = {
        "The engine shall say the number itself.",
        "When no rule matches a number, the engine shall say the number itself.",
        "While the upper bound is below 1, the engine shall say nothing.",
        "If the rule list is empty, then the engine shall reject the configuration.",
        "Where logging is included, the engine shall log each number.",
        "While the engine is started, when a number is given, the engine shall say it",
        "Where logging is included, while debugging, if a rule fails, then the engine shall log the rule.",
        "When a number matches Fizz, Buzz, or both, the engine shall concatenate their words."
    })
    void earsSentenceWithModuleAsSubject_producesNoErrors(String requirement) {
        var result = compile(moduleWithRequirements("com.example.engine", "engine", requirement));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "The engine must say the number itself.",
        "Wen no rule matches, the engine shall say the number itself.",
        "If the rule list is empty, the engine shall reject the configuration.",
        "when no rule matches, the engine shall say the number itself.",
        "Engine shall say the number itself.",
        "The engine shall",
        "   "
    })
    void sentenceNotInEarsFormat_producesEarsError(String requirement) {
        var result = compile(moduleWithRequirements("com.example.engine", "engine", requirement));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requirement of module 'engine' is not an EARS sentence")
            .contains("When <trigger>, the engine shall <response>.");
    }

    @Test
    void sentenceWithAnotherSubject_producesSubjectError() {
        var result = compile(moduleWithRequirements("com.example.engine", "engine",
            "When no rule matches, the system shall say the number itself."));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("has 'system' as subject")
            .contains("write \"the engine shall ...\"");
    }

    @Test
    void subjectIsComparedIgnoringCase() {
        var result = compile(moduleWithRequirements("com.example.billing", "Billing",
            "The billing shall send one invoice per month."));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void requirementOnPackageWithoutModule_producesError() {
        var result = compile(source("com.example.plain.package-info",
            "@Req(\"The plain shall do nothing.\")\n" +
            "package com.example.plain;\n" +
            "import org.asymetrik.modular.specification.Req;\n"));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requirement on package 'com.example.plain', which is not a @Module");
    }

    @Test
    void onlyTheInvalidRequirementOfSeveral_producesAnError() {
        var result = compile(moduleWithRequirements("com.example.engine", "engine",
            "The engine shall say the number itself.",
            "The engine says nothing.",
            "When asked, the engine shall answer."));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("\"The engine says nothing.\"");
    }

    @Test
    void duplicateIdsAcrossModules_produceDuplicateIdError() {
        var result = compile(
            source("com.example.engine.package-info",
                "@Module(name = \"engine\", exports = {\".\"})\n" +
                "@Req(id = \"REQ-1\", value = \"The engine shall say numbers.\")\n" +
                "package com.example.engine;\n" +
                "import org.asymetrik.modular.annotations.Module;\n" +
                "import org.asymetrik.modular.specification.Req;\n"),
            source("com.example.rules.package-info",
                "@Module(name = \"rules\", exports = {\".\"})\n" +
                "@Req(id = \"REQ-1\", value = \"The rules shall match multiples.\")\n" +
                "package com.example.rules;\n" +
                "import org.asymetrik.modular.annotations.Module;\n" +
                "import org.asymetrik.modular.specification.Req;\n")
        );

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Duplicate requirement id 'REQ-1'");
    }

    @Test
    void requirementsWithoutId_areNotDuplicates() {
        var result = compile(moduleWithRequirements("com.example.engine", "engine",
            "The engine shall say numbers.",
            "The engine shall say words."));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
    }

    @Test
    void requirementsWithId_areWrittenToTheIndex() throws IOException {
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine",
            "ENG-1", "When no rule matches a number, the engine shall say the number itself.",
            "ENG-2", "The engine shall say words."));
        compile(moduleWithRequirements("com.example.plain", "plain", "The plain shall have no id."));

        assertThat(out.resolve(RequirementProcessor.INDEX)).content()
            .contains("ENG-1=engine|When no rule matches a number, the engine shall say the number itself.")
            .contains("ENG-2=engine|The engine shall say words.")
            .doesNotContain("plain");
    }

    @Test
    void recompilingSomeModules_keepsTheIndexEntriesOfTheOthers() throws IOException {
        compile(
            moduleWithIdentifiedRequirements("com.example.engine", "engine", "ENG-1", "The engine shall say numbers."),
            moduleWithIdentifiedRequirements("com.example.rules", "rules", "RUL-1", "The rules shall match.")
        );
        // Incremental build: only engine is recompiled, and ENG-1 was replaced by ENG-2
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine", "ENG-2", "The engine shall say words."));

        assertThat(out.resolve(RequirementProcessor.INDEX)).content()
            .contains("RUL-1=rules|")
            .contains("ENG-2=engine|")
            .doesNotContain("ENG-1");
    }

    @Test
    void testReqWithIdsFromTheIndexOnTheClassPath_producesNoErrors() {
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine",
            "ENG-1", "The engine shall say numbers.",
            "ENG-2", "The engine shall say words."));

        var result = compileTests(testClass("com.example.engine.FizzBuzzTest",
            "@TestReq(\"ENG-1\") void saysNumbers() {}",
            "@TestReq({\"ENG-1\", \"ENG-2\"}) void saysWords() {}"));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR || d.getKind() == WARNING).isEmpty();
    }

    @Test
    void testReqOnClass_coversTheRequirement() {
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine", "ENG-1", "The engine shall say numbers."));

        var result = compileTests(source("com.example.engine.FizzBuzzTest",
            "package com.example.engine;\n" +
            "import org.asymetrik.modular.specification.TestReq;\n" +
            "@TestReq(\"ENG-1\") class FizzBuzzTest { void saysNumbers() {} }\n"));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR || d.getKind() == WARNING).isEmpty();
    }

    @Test
    void testReqWithUnknownId_producesUnknownIdError() {
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine", "ENG-1", "The engine shall say numbers."));

        var result = compileTests(testClass("com.example.engine.FizzBuzzTest",
            "@TestReq({\"ENG-1\", \"ENG-9\"}) void saysNumbers() {}"));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Unknown requirement id 'ENG-9' in @TestReq")
            .contains("Known ids: ENG-1");
    }

    @Test
    void requirementWithoutTest_producesCoverageWarning() {
        compile(moduleWithIdentifiedRequirements("com.example.engine", "engine",
            "ENG-1", "The engine shall say numbers.",
            "ENG-2", "The engine shall say words."));

        var result = compileTests(testClass("com.example.engine.FizzBuzzTest",
            "@TestReq(\"ENG-1\") void saysNumbers() {}"));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
        assertThat(result)
            .filteredOn(d -> d.getKind() == WARNING)
            .hasSize(1)
            .first()
            .extracting(d -> d.getMessage(null))
            .asString()
            .contains("Requirement 'ENG-2' of module 'engine' has no test: \"The engine shall say words.\"")
            .contains("Add @TestReq(\"ENG-2\")");
    }

    @Test
    void testReqAndRequirementsInTheSameCompilation_areChecked() {
        var result = compile(
            moduleWithIdentifiedRequirements("com.example.engine", "engine", "ENG-1", "The engine shall say numbers."),
            testClass("com.example.engine.FizzBuzzTest", "@TestReq(\"ENG-2\") void saysNumbers() {}"));

        assertThat(result)
            .filteredOn(d -> d.getKind() == ERROR)
            .extracting(d -> d.getMessage(null))
            .anySatisfy(message -> assertThat(message).contains("Unknown requirement id 'ENG-2'"));
    }

    @Test
    void testReqWithoutAnyKnownRequirement_producesWarning() {
        var result = compileTests(testClass("com.example.engine.FizzBuzzTest",
            "@TestReq(\"ENG-1\") void saysNumbers() {}"));

        assertThat(result).filteredOn(d -> d.getKind() == ERROR).isEmpty();
        assertThat(result)
            .filteredOn(d -> d.getKind() == WARNING)
            .extracting(d -> d.getMessage(null))
            .anySatisfy(message -> assertThat(message).contains("Cannot check @TestReq ids"));
    }

    // --- helpers ---

    private static JavaFileObject moduleWithIdentifiedRequirements(String pkg, String moduleName,
                                                                  String... idsAndRequirements) {
        StringBuilder source = new StringBuilder(String.format("@Module(name = \"%s\", exports = {\".\"})%n", moduleName));
        for (int i = 0; i < idsAndRequirements.length; i += 2) {
            source.append(String.format("@Req(id = \"%s\", value = \"%s\")%n", idsAndRequirements[i], idsAndRequirements[i + 1]));
        }
        source.append(String.format("package %s;%n", pkg))
            .append("import org.asymetrik.modular.annotations.Module;\n")
            .append("import org.asymetrik.modular.specification.Req;\n");
        return source(pkg + ".package-info", source.toString());
    }

    private static JavaFileObject testClass(String fqn, String... methods) {
        int lastDot = fqn.lastIndexOf('.');
        return source(fqn, String.format("package %s;%nimport org.asymetrik.modular.specification.TestReq;%n" +
            "class %s { %s }%n", fqn.substring(0, lastDot), fqn.substring(lastDot + 1), String.join(" ", methods)));
    }

    private static JavaFileObject moduleWithRequirements(String pkg, String moduleName, String... requirements) {
        StringBuilder source = new StringBuilder(String.format("@Module(name = \"%s\", exports = {\".\"})%n", moduleName));
        for (String requirement : requirements) {
            source.append(String.format("@Req(\"%s\")%n", requirement));
        }
        source.append(String.format("package %s;%n", pkg))
            .append("import org.asymetrik.modular.annotations.Module;\n")
            .append("import org.asymetrik.modular.specification.Req;\n");
        return source(pkg + ".package-info", source.toString());
    }

    private static JavaFileObject source(String fqn, String source) {
        URI uri = URI.create("string:///" + fqn.replace('.', '/') + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    /** Compiles the main code into {@link #out}. */
    private List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(JavaFileObject... sources) {
        return compile(out, System.getProperty("java.class.path"), sources);
    }

    /** Compiles tests into {@link #testOut}, with the main code's output {@link #out} on the class path. */
    private List<javax.tools.Diagnostic<? extends JavaFileObject>> compileTests(JavaFileObject... sources) {
        return compile(testOut, out + File.pathSeparator + System.getProperty("java.class.path"), sources);
    }

    private List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(Path outputDir, String classPath,
                                                                          JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, null)) {
            List<String> options = List.of(
                "-classpath", classPath,
                "-d", outputDir.toString(),
                "--release", "21",
                "-proc:only"
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, collector, options, null, List.of(sources)
            );
            task.setProcessors(List.of(new RequirementProcessor()));
            task.call();
        } catch (Exception e) {
            throw new RuntimeException("Compilation failed unexpectedly", e);
        }
        return collector.getDiagnostics();
    }
}
