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
package org.asymetrik.modular.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.asymetrik.modular.report.Finding.Kind;
import org.asymetrik.modular.report.Finding.Level;
import org.asymetrik.modular.verification.ModularVerifier.DuplicateModuleNameViolation;
import org.asymetrik.modular.verification.ModularVerifier.ExportViolation;
import org.asymetrik.modular.verification.ModularVerifier.Invalid;
import org.asymetrik.modular.verification.ModularVerifier.NestedModuleViolation;
import org.asymetrik.modular.verification.ModularVerifier.RequiresViolation;
import org.asymetrik.modular.verification.ModularVerifier.UnknownRequiredModuleViolation;
import org.asymetrik.modular.verification.ModularVerifier.UnmanagedDependencyViolation;
import org.asymetrik.modular.verification.ModuleAnalysis;
import org.asymetrik.modular.verification.ModuleAnalysis.ClassUse;
import org.asymetrik.modular.verification.ModuleAnalysis.Dependency;
import org.asymetrik.modular.verification.ModuleAnalysis.ModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.TargetKind;
import org.asymetrik.modular.verification.ModuleAnalysis.TypeDescription;

/** Works out the findings of a report. */
final class Findings {

	private final List<Finding> all = new ArrayList<>();
	private final Map<Integer, Set<String>> requiresNotUsed = new TreeMap<>();
	private final Map<Integer, Set<String>> exportsNotUsed = new TreeMap<>();

	Findings(ModuleAnalysis analysis, ModuleGraph graph) {
		for (Invalid violation : analysis.violations()) {
			all.add(new Finding(Level.ERROR, Kind.VIOLATION, titleOf(violation), detailsOf(violation)));
		}
		for (List<String> cycle : graph.cycles()) {
			List<String> uses = graph.edgesOf(cycle).stream()
				.map(edge -> "'" + edge.from() + "' uses '" + edge.to() + "' (" + Text.count(edge.uses(), "use") + ")")
				.toList();
			all.add(new Finding(Level.WARNING, Kind.CYCLE, "Cycle between " + Text.quotedList(cycle), uses));
		}
		// A class in a virtual module has been given a place
		List<String> unassigned = analysis.unassignedClasses().stream()
			.filter(className -> analysis.externalModules().stream()
				.flatMap(external -> external.packages().stream())
				.noneMatch(covered -> className.startsWith(covered + ".")))
			.toList();
		if (!unassigned.isEmpty()) {
			String title = unassigned.size() == 1 ? "1 class is in no module"
				: unassigned.size() + " classes are in no module";
			all.add(new Finding(Level.WARNING, Kind.CLASSES_OUTSIDE_MODULES, title, unassigned));
		}
		findRequiresNotUsed(analysis);
		findExportsNotUsed(analysis);
	}

	/** Errors first, then warnings, then hints. */
	List<Finding> all() {
		return List.copyOf(all);
	}

	int count(Level level) {
		return (int) all.stream().filter(finding -> finding.level() == level).count();
	}

	/** The names that the module at this index of {@link ModuleAnalysis#modules()} requires without using them. */
	Set<String> requiresNotUsed(int moduleIndex) {
		return requiresNotUsed.getOrDefault(moduleIndex, Set.of());
	}

	/** The exports of the module at this index that no other module uses, as written in the annotation. */
	Set<String> exportsNotUsed(int moduleIndex) {
		return exportsNotUsed.getOrDefault(moduleIndex, Set.of());
	}

	private void findRequiresNotUsed(ModuleAnalysis analysis) {
		// A name that does not exist is already a violation
		Set<List<String>> unknown = new LinkedHashSet<>();
		for (Invalid violation : analysis.violations()) {
			if (violation instanceof UnknownRequiredModuleViolation v) {
				unknown.add(List.of(v.moduleName(), v.requiredModuleName()));
			}
		}
		Set<List<String>> used = new LinkedHashSet<>();
		for (Dependency dependency : analysis.dependencies()) {
			if (dependency.target().kind() != TargetKind.UNMANAGED) {
				used.add(List.of(dependency.moduleName(), dependency.target().name()));
			}
		}
		List<ModuleDescription> modules = analysis.modules();
		for (int i = 0; i < modules.size(); i++) {
			ModuleDescription module = modules.get(i);
			for (String required : new LinkedHashSet<>(module.requires())) {
				List<String> key = List.of(module.name(), required);
				if (!used.contains(key) && !unknown.contains(key)) {
					requiresNotUsed.computeIfAbsent(i, index -> new LinkedHashSet<>()).add(required);
					all.add(new Finding(Level.HINT, Kind.REQUIRES_NOT_USED,
						"'" + module.name() + "' requires '" + required + "', but no use of it was found",
						List.of()));
				}
			}
		}
	}

	private void findExportsNotUsed(ModuleAnalysis analysis) {
		List<ModuleDescription> modules = analysis.modules();
		Map<String, String> packageOfType = new HashMap<>();
		for (ModuleDescription module : modules) {
			for (TypeDescription type : module.types()) {
				packageOfType.put(type.name(), type.packageName());
			}
		}
		// For each module name, the packages that other modules use
		Map<String, Set<String>> usedPackages = new HashMap<>();
		for (Dependency dependency : analysis.dependencies()) {
			if (dependency.target().kind() == TargetKind.MODULE
				&& !dependency.moduleName().equals(dependency.target().name())) {
				Set<String> packages = usedPackages.computeIfAbsent(dependency.target().name(),
					name -> new LinkedHashSet<>());
				for (ClassUse use : dependency.uses()) {
					packages.add(packageOfType.getOrDefault(use.usedClass(), packageOf(use.usedClass())));
				}
			}
		}
		for (int i = 0; i < modules.size(); i++) {
			ModuleDescription module = modules.get(i);
			Set<String> used = usedPackages.getOrDefault(module.name(), Set.of());
			for (String export : new LinkedHashSet<>(module.exports())) {
				if (used.stream().noneMatch(packageName -> exports(export, packageName, module))) {
					exportsNotUsed.computeIfAbsent(i, index -> new LinkedHashSet<>()).add(export);
					String what = export.equals(".") ? "its own package" : "'" + export + "'";
					all.add(new Finding(Level.HINT, Kind.EXPORT_NOT_USED,
						"'" + module.name() + "' exports " + what + ", but no other module uses it", List.of()));
				}
			}
		}
	}

	/** Whether this entry of a module's {@code exports} covers the package: the verifier's rule. */
	private static boolean exports(String export, String packageName, ModuleDescription module) {
		if (export.equals(".")) {
			return packageName.equals(module.packageName());
		}
		if (export.endsWith("*")) {
			return packageName.startsWith(export.substring(0, export.length() - 1));
		}
		return export.equals(packageName);
	}

	private static String packageOf(String className) {
		int lastDot = className.lastIndexOf('.');
		return lastDot < 0 ? "" : className.substring(0, lastDot);
	}

	private static String titleOf(Invalid violation) {
		return switch (violation) {
			case NestedModuleViolation v ->
				"Module '" + v.nestedModuleName() + "' is nested in module '" + v.parentModuleName() + "'";
			case ExportViolation v -> "'" + v.consumerModuleName() + "' uses a class that '" + v.providerModuleName()
				+ "' does not export";
			case RequiresViolation v ->
				"'" + v.consumerModuleName() + "' uses '" + v.providerModuleName() + "' without requiring it";
			case DuplicateModuleNameViolation v -> "Two modules are named '" + v.moduleName() + "'";
			case UnknownRequiredModuleViolation v ->
				"'" + v.moduleName() + "' requires '" + v.requiredModuleName() + "', which does not exist";
			case UnmanagedDependencyViolation v ->
				"'" + v.consumerModuleName() + "' uses a class that is in no module";
		};
	}

	/** The lines of the verifier's message, without its heading. */
	private static List<String> detailsOf(Invalid violation) {
		return violation.formatError().lines().skip(1).map(String::strip).filter(line -> !line.isEmpty()).toList();
	}
}
