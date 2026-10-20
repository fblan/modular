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

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.asymetrik.modular.specification.Req;
import org.asymetrik.modular.specification.Reqs;
import org.asymetrik.modular.specification.TestReq;

@SupportedAnnotationTypes({
    "org.asymetrik.modular.specification.Req",
    "org.asymetrik.modular.specification.Reqs",
    "org.asymetrik.modular.specification.TestReq"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class RequirementProcessor extends AbstractProcessor {

    /** Read by name, so that this module does not depend on the annotations module. */
    static final String MODULE_ANNOTATION = "org.asymetrik.modular.annotations.Module";

    /**
     * An EARS sentence: optional feature, state and trigger clauses in any order, an optional unwanted-behaviour
     * clause, then "the &lt;system&gt; shall &lt;response&gt;". Group 1 is the system, group 2 the response.
     */
    static final Pattern EARS = Pattern.compile(
        "(?:(?:Where|While|When) .+?, )*(?:If .+?, then )?the (.+?) shall (\\S.*)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Index of the requirements with an id, written to the class output when compiling the main code, and read
     * from the class path when compiling the tests. One line per requirement: {@code <id>=<module>|<sentence>}.
     */
    static final String INDEX = "META-INF/modular/requirements.properties";

    private record Requirement(String module, String sentence) {}

    /** Requirements with an id declared in this compilation, by id. */
    private final Map<String, Requirement> declared = new LinkedHashMap<>();

    /** Modules whose requirements are compiled now: their entries in an existing index are replaced. */
    private final Set<String> compiledModules = new HashSet<>();

    /** Requirements of the index found on the class path (the main code, when compiling tests), read once. */
    private Map<String, Requirement> classPathIndex;

    /** Whether this compilation has {@link TestReq}s, and the ids they cover. */
    private boolean hasTests;
    private final Set<String> covered = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!declared.isEmpty()) writeIndex();
            if (hasTests) reportCoverage();
            return false;
        }

        Set<Element> annotated = new LinkedHashSet<>();
        annotated.addAll(roundEnv.getElementsAnnotatedWith(Req.class));
        annotated.addAll(roundEnv.getElementsAnnotatedWith(Reqs.class));

        for (Element element : annotated) {
            if (!(element instanceof PackageElement pkg)) continue;

            Optional<String> moduleName = findModuleName(pkg);
            for (Req req : pkg.getAnnotationsByType(Req.class)) {
                if (moduleName.isEmpty()) {
                    error(pkg, "Requirement on package '%s', which is not a @Module: requirements describe a " +
                        "module, so @Req must be next to @Module. Requirement: \"%s\"", pkg.getQualifiedName(),
                        req.value());
                    continue;
                }
                compiledModules.add(moduleName.get());
                checkSentence(pkg, moduleName.get(), req.value());
                checkUniqueId(pkg, moduleName.get(), req);
            }
        }

        // After the requirements of the round, so that tests compiled with them can reference them. Checked now
        // rather than when processing is over, as only then do errors point at the test in the source.
        for (Element element : roundEnv.getElementsAnnotatedWith(TestReq.class)) {
            hasTests = true;
            checkTestIds(element, element.getAnnotation(TestReq.class).value());
        }
        return true;
    }

    private void checkSentence(PackageElement pkg, String moduleName, String requirement) {
        String sentence = requirement.strip();
        if (sentence.endsWith(".")) {
            sentence = sentence.substring(0, sentence.length() - 1);
        }

        Matcher matcher = EARS.matcher(sentence);
        if (sentence.isEmpty() || !Character.isUpperCase(sentence.charAt(0)) || !matcher.matches()) {
            error(pkg, "Requirement of module '%s' is not an EARS sentence: \"%s\". Use one of:%n" +
                "  The %1$s shall <response>.%n" +
                "  When <trigger>, the %1$s shall <response>.%n" +
                "  While <state>, the %1$s shall <response>.%n" +
                "  If <condition>, then the %1$s shall <response>.%n" +
                "  Where <feature is included>, the %1$s shall <response>.%n" +
                "Clauses can be combined, e.g. While <state>, when <trigger>, the %1$s shall <response>.",
                moduleName, requirement);
            return;
        }

        String system = matcher.group(1);
        if (!system.equalsIgnoreCase(moduleName)) {
            error(pkg, "Requirement of module '%s' has '%s' as subject: \"%s\". The subject of a requirement is " +
                "its module, write \"the %1$s shall ...\".", moduleName, system, requirement);
        }
    }

    /** Ids are only compared within the compilation: an incremental build may not recompile every module. */
    private void checkUniqueId(PackageElement pkg, String moduleName, Req req) {
        if (req.id().isBlank()) return;
        Requirement first = declared.putIfAbsent(req.id(), new Requirement(moduleName, req.value()));
        if (first != null) {
            error(pkg, "Duplicate requirement id '%s': already used in module '%s'. Requirement: \"%s\"",
                req.id(), first.module(), req.value());
        }
    }

    /**
     * Writes the index of the requirements with an id. Entries of an existing index are kept for the modules not
     * compiled now, so that an incremental build does not lose them.
     */
    private void writeIndex() {
        Map<String, Requirement> index = new TreeMap<>();
        readIndex(StandardLocation.CLASS_OUTPUT).forEach((id, requirement) -> {
            if (!compiledModules.contains(requirement.module())) index.put(id, requirement);
        });
        index.putAll(declared);

        Properties properties = new Properties();
        index.forEach((id, requirement) -> properties.setProperty(id, requirement.module() + "|" + requirement.sentence()));
        try {
            StringWriter content = new StringWriter();
            properties.store(content, null);
            // Drop the date comment that Properties writes, and sort, so that the index only changes with the requirements
            List<String> lines = new ArrayList<>(content.toString().lines().filter(l -> !l.startsWith("#")).sorted().toList());
            lines.add(0, "# Requirements with an id, written by RequirementProcessor: <id>=<module>|<sentence>");
            FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", INDEX);
            try (Writer writer = file.openWriter()) {
                writer.write(String.join("\n", lines) + "\n");
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Cannot write the requirement index " + INDEX + ": " + e.getMessage() +
                ". @TestReq ids cannot be checked against these requirements.");
        }
    }

    private Map<String, Requirement> readIndex(StandardLocation location) {
        Map<String, Requirement> index = new TreeMap<>();
        try {
            FileObject file = processingEnv.getFiler().getResource(location, "", INDEX);
            Properties properties = new Properties();
            try (InputStream in = file.openInputStream()) {
                properties.load(in);
            }
            for (String id : properties.stringPropertyNames()) {
                String[] moduleAndSentence = properties.getProperty(id).split("\\|", 2);
                if (moduleAndSentence.length == 2) {
                    index.put(id, new Requirement(moduleAndSentence[0], moduleAndSentence[1]));
                }
            }
        } catch (IOException | IllegalArgumentException noIndex) {
            // No index there yet
        }
        return index;
    }

    /** Requirements known to tests: those of the index on the class path, and those of this compilation. */
    private Map<String, Requirement> knownRequirements() {
        if (classPathIndex == null) {
            classPathIndex = readIndex(StandardLocation.CLASS_PATH);
        }
        Map<String, Requirement> known = new TreeMap<>(classPathIndex);
        known.putAll(declared);
        return known;
    }

    private void checkTestIds(Element test, String[] ids) {
        Map<String, Requirement> known = knownRequirements();
        if (known.isEmpty()) return; // reported once, by reportCoverage()

        for (String id : ids) {
            if (known.containsKey(id)) {
                covered.add(id);
            } else {
                error(test, "Unknown requirement id '%s' in @TestReq: no @Req has this id. Known ids: %s",
                    id, String.join(", ", known.keySet()));
            }
        }
    }

    /** Warns about the requirements with an id that no {@link TestReq} of this compilation covers. */
    private void reportCoverage() {
        Map<String, Requirement> known = knownRequirements();
        if (known.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Cannot check @TestReq ids: no requirement with an id was found, neither in this compilation nor in " +
                INDEX + " on the class path. Is RequirementProcessor enabled when compiling the main code?");
            return;
        }

        known.forEach((id, requirement) -> {
            if (!covered.contains(id)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, String.format(
                    "Requirement '%s' of module '%s' has no test: \"%s\". Add @TestReq(\"%s\") to a test that checks it.",
                    id, requirement.module(), requirement.sentence(), id));
            }
        });
    }

    private Optional<String> findModuleName(PackageElement pkg) {
        for (AnnotationMirror mirror : pkg.getAnnotationMirrors()) {
            Element type = mirror.getAnnotationType().asElement();
            if (type instanceof TypeElement typeElement
                && typeElement.getQualifiedName().contentEquals(MODULE_ANNOTATION)) {
                return mirror.getElementValues().entrySet().stream()
                    .filter(e -> e.getKey().getSimpleName().contentEquals("name"))
                    .map(e -> String.valueOf(e.getValue().getValue()))
                    .findFirst();
            }
        }
        return Optional.empty();
    }

    private void error(Element element, String format, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(format, args), element);
    }
}
