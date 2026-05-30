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
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import org.asymetrik.modular.api.Module;

@SupportedAnnotationTypes("org.asymetrik.modular.api.Module")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ModuleAnnotationProcessor extends AbstractProcessor {

    private record ModuleEntry(String name, String packageName, Element element) {
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
                modules.add(new ModuleEntry(annotation.name(), pkg.getQualifiedName().toString(), element));
            }
        }

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

        return true;
    }
}