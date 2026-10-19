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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import org.asymetrik.modular.specification.Req;
import org.asymetrik.modular.specification.Reqs;

@SupportedAnnotationTypes({
    "org.asymetrik.modular.specification.Req",
    "org.asymetrik.modular.specification.Reqs"
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

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        Set<Element> annotated = new LinkedHashSet<>();
        annotated.addAll(roundEnv.getElementsAnnotatedWith(Req.class));
        annotated.addAll(roundEnv.getElementsAnnotatedWith(Reqs.class));

        // Ids are only compared within the round: an incremental build may not recompile every module.
        Map<String, String> moduleById = new HashMap<>();
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
                checkSentence(pkg, moduleName.get(), req.value());
                checkUniqueId(pkg, moduleName.get(), req, moduleById);
            }
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

    private void checkUniqueId(PackageElement pkg, String moduleName, Req req, Map<String, String> moduleById) {
        if (req.id().isBlank()) return;
        String first = moduleById.putIfAbsent(req.id(), moduleName);
        if (first != null) {
            error(pkg, "Duplicate requirement id '%s': already used in module '%s'. Requirement: \"%s\"",
                req.id(), first, req.value());
        }
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
