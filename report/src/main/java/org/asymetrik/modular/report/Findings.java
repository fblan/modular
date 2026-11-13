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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
import org.asymetrik.modular.verification.ModuleAnalysis.MethodDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.ModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.Target;
import org.asymetrik.modular.verification.ModuleAnalysis.TargetKind;
import org.asymetrik.modular.verification.ModuleAnalysis.TypeDescription;

/** Works out the findings of a report. */
final class Findings {

	/** Above this many types, a module is a {@link Kind#LARGE_MODULE}. */
	static final int MAX_TYPES = 50;
	/** Above this many public methods, a type is a {@link Kind#LARGE_TYPE}. */
	static final int MAX_PUBLIC_METHODS = 20;
	/** Above this many lines of code, a method is a {@link Kind#LARGE_METHOD}: SonarQube's default (rule S138). */
	static final int MAX_LINES = 75;
	/** The largest types that the details of a {@link Kind#LARGE_MODULE} name. */
	private static final int LARGEST_TYPES_SHOWN = 3;

	private final List<Finding> all = new ArrayList<>();
	private final Map<Integer, Set<String>> requiresNotUsed = new TreeMap<>();
	private final Map<Integer, Set<String>> exportsNotUsed = new TreeMap<>();

	Findings(ModuleAnalysis analysis, ModuleGraph graph) {
		for (Invalid violation : analysis.violations()) {
			all.add(new Finding(Level.ERROR, Kind.VIOLATION, titleOf(violation), detailsOf(violation),
				List.of(moduleOf(violation))));
		}
		for (List<String> cycle : graph.cycles()) {
			List<String> uses = graph.edgesOf(cycle).stream()
				.map(edge -> "'" + edge.from() + "' uses '" + edge.to() + "' (" + Text.count(edge.uses(), "use") + ")")
				.toList();
			all.add(new Finding(Level.WARNING, Kind.CYCLE, "Cycle between " + Text.quotedList(cycle), uses, cycle));
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
		findLargeModules(analysis);
		findRequiresNotUsed(analysis);
		findExportsNotUsed(analysis);
		findLargeTypes(analysis);
		findLargeMethods(analysis);
		findLeakyApis(analysis);
		findBorrowedExceptions(analysis);
		findExportedHelpers(analysis);
	}

	/** Errors first, then warnings, then hints. */
	List<Finding> all() {
		return List.copyOf(all);
	}

	int count(Level level) {
		return (int) all.stream().filter(finding -> finding.level() == level).count();
	}

	/** The findings about the module with this name, in the order of {@link #all()}. */
	List<Finding> about(String moduleName) {
		return all.stream().filter(finding -> finding.modules().contains(moduleName)).toList();
	}

	/** The most serious level of the findings about each module that has some. */
	Map<String, Level> levelOfModules() {
		Map<String, Level> levels = new HashMap<>();
		for (Finding finding : all) {
			for (String module : finding.modules()) {
				levels.merge(module, finding.level(), (one, other) -> one.compareTo(other) <= 0 ? one : other);
			}
		}
		return levels;
	}

	private void findLargeModules(ModuleAnalysis analysis) {
		for (ModuleDescription module : analysis.modules()) {
			if (module.types().size() <= MAX_TYPES) {
				continue;
			}
			List<String> details = new ArrayList<>();
			String size = Text.count(publicMethods(module), "public method");
			OptionalInt lines = linesOfCode(module);
			if (lines.isPresent()) {
				size += ", " + Text.count(lines.getAsInt(), "line") + " of code";
			}
			details.add(size);
			module.types().stream()
				.filter(type -> type.linesOfCode().isPresent())
				.sorted(Comparator.comparingInt((TypeDescription type) -> type.linesOfCode().getAsInt()).reversed()
					.thenComparing(TypeDescription::name))
				.limit(LARGEST_TYPES_SHOWN)
				.forEach(type -> {
					int typeLines = type.linesOfCode().getAsInt();
					details.add(type.name() + ": " + Text.count(typeLines, "line") + " of code"
						+ (lines.isPresent() && lines.getAsInt() > 0
							? ", " + Math.round(100.0 * typeLines / lines.getAsInt()) + "% of '" + module.name() + "'"
							: ""));
				});
			details.add("A module with many types usually holds several parts of the domain: split it by part.");
			all.add(new Finding(Level.WARNING, Kind.LARGE_MODULE, "'" + module.name() + "' has "
				+ Text.count(module.types().size(), "type") + ", more than " + MAX_TYPES, details,
				List.of(module.name())));
		}
	}

	/** The public methods of a module's types. */
	static int publicMethods(ModuleDescription module) {
		return module.types().stream().mapToInt(TypeDescription::publicMethods).sum();
	}

	/** The lines of code of a module, empty when those of one of its types are not known. */
	static OptionalInt linesOfCode(ModuleDescription module) {
		int lines = 0;
		for (TypeDescription type : module.types()) {
			if (type.linesOfCode().isEmpty()) {
				return OptionalInt.empty();
			}
			lines += type.linesOfCode().getAsInt();
		}
		return OptionalInt.of(lines);
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
				// Every class uses java.base, so its uses are not recorded
				if (!used.contains(key) && !unknown.contains(key) && !required.equals("java.base")) {
					requiresNotUsed.computeIfAbsent(i, index -> new LinkedHashSet<>()).add(required);
					all.add(new Finding(Level.HINT, Kind.REQUIRES_NOT_USED,
						"'" + module.name() + "' requires '" + required + "', but no use of it was found",
						List.of(), List.of(module.name())));
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
						"'" + module.name() + "' exports " + what + ", but no other module uses it", List.of(),
						List.of(module.name())));
				}
			}
		}
	}

	private void findLargeTypes(ModuleAnalysis analysis) {
		for (ModuleDescription module : analysis.modules()) {
			for (TypeDescription type : module.types()) {
				if (type.publicMethods() <= MAX_PUBLIC_METHODS) {
					continue;
				}
				all.add(new Finding(Level.HINT, Kind.LARGE_TYPE, "'" + type.name() + "', in '" + module.name()
					+ "', has " + Text.count(type.publicMethods(), "public method") + ", more than " + MAX_PUBLIC_METHODS,
					List.of("A type with many public methods usually does several jobs: split it by job."),
					List.of(module.name())));
			}
		}
	}

	private void findLargeMethods(ModuleAnalysis analysis) {
		for (ModuleDescription module : analysis.modules()) {
			for (TypeDescription type : module.types()) {
				for (MethodDescription method : type.methodsWithCode()) {
					if (method.linesOfCode() <= MAX_LINES) {
						continue;
					}
					String name = type.packageName().isEmpty() ? method.name()
						: type.packageName() + "." + method.name();
					all.add(new Finding(Level.HINT, Kind.LARGE_METHOD, "'" + name + "', in '" + module.name()
						+ "', has " + method.linesOfCode() + " lines of code, more than " + MAX_LINES,
						List.of("A long method usually does several things: split it into methods that each do one."),
						List.of(module.name())));
				}
			}
		}
	}

	private void findLeakyApis(ModuleAnalysis analysis) {
		Map<String, ModuleDescription> modulesByName = new HashMap<>();
		Set<String> exportedTypes = new HashSet<>();
		for (ModuleDescription module : analysis.modules()) {
			modulesByName.putIfAbsent(module.name(), module);
			module.types().stream().filter(TypeDescription::exported).forEach(type -> exportedTypes.add(type.name()));
		}
		for (Dependency exposure : analysis.dependencies()) {
			Target exposed = exposure.target();
			// java.* is free, and an unmanaged package is already a violation
			if (exposed.kind() == TargetKind.UNMANAGED
				|| exposed.kind() == TargetKind.JPMS_MODULE && exposed.name().startsWith("java.")) {
				continue;
			}
			// An annotation on an exported type is nothing its users have to know
			List<ClassUse> exposing = exposure.uses().stream()
				.filter(use -> exportedTypes.contains(use.consumerClass()) && !use.annotationsOnly())
				.toList();
			Set<String> exposingTypes = new TreeSet<>();
			exposing.forEach(use -> exposingTypes.add(use.consumerClass()));
			List<String> lacking = new ArrayList<>();
			List<String> receiving = new ArrayList<>();
			for (Dependency consumer : analysis.dependencies()) {
				if (consumer.target().kind() != TargetKind.MODULE
					|| !consumer.target().name().equals(exposure.moduleName())
					|| consumer.moduleName().equals(exposed.name())
					|| modulesByName.get(consumer.moduleName()).requires().contains(exposed.name())) {
					continue;
				}
				List<ClassUse> received = consumer.uses().stream()
					.filter(use -> exposingTypes.contains(use.usedClass()))
					.toList();
				if (!received.isEmpty()) {
					lacking.add(consumer.moduleName());
					received.forEach(use -> receiving.add(use.consumerClass() + " uses " + use.usedClass()));
				}
			}
			if (lacking.isEmpty()) {
				continue;
			}
			List<String> details = new ArrayList<>();
			exposing.forEach(use -> details.add(use.consumerClass() + " uses " + use.usedClass()));
			details.addAll(receiving);
			details.add("Keep '" + exposed.name() + "' out of the exported types of '" + exposure.moduleName()
				+ "', or require '" + exposed.name() + "' where they are used.");
			// The module that leaks, and the ones that receive
			List<String> about = new ArrayList<>(List.of(exposure.moduleName()));
			about.addAll(lacking);
			all.add(new Finding(Level.HINT, Kind.LEAKY_API, Text.quotedList(lacking) + " "
				+ (lacking.size() == 1 ? "uses" : "use") + " exported types of '" + exposure.moduleName()
				+ "' that use '" + exposed.name() + "', without requiring '" + exposed.name() + "'", details, about));
		}
	}

	private void findBorrowedExceptions(ModuleAnalysis analysis) {
		Set<String> exceptions = new HashSet<>();
		for (ModuleDescription module : analysis.modules()) {
			module.types().stream().filter(TypeDescription::exception).forEach(type -> exceptions.add(type.name()));
		}
		for (Dependency dependency : analysis.dependencies()) {
			if (dependency.target().kind() != TargetKind.MODULE) {
				continue;
			}
			List<String> details = new ArrayList<>(dependency.uses().stream()
				.filter(use -> use.createsInstances() && exceptions.contains(use.usedClass()))
				.map(use -> use.consumerClass() + " creates " + use.usedClass())
				.toList());
			if (details.isEmpty()) {
				continue;
			}
			details.add("Its errors then depend on how '" + dependency.target().name() + "' handles them: give '"
				+ dependency.moduleName() + "' exceptions of its own. Catching them is fine.");
			all.add(new Finding(Level.HINT, Kind.BORROWED_EXCEPTION, "'" + dependency.moduleName()
				+ "' creates exceptions of '" + dependency.target().name() + "'", details,
				List.of(dependency.moduleName())));
		}
	}

	private void findExportedHelpers(ModuleAnalysis analysis) {
		for (ModuleDescription module : analysis.modules()) {
			for (TypeDescription type : module.types()) {
				if (!type.exported() || !type.staticHelper()) {
					continue;
				}
				Set<String> users = new TreeSet<>();
				List<String> details = new ArrayList<>();
				for (Dependency dependency : analysis.dependencies()) {
					if (dependency.target().kind() == TargetKind.MODULE
						&& dependency.target().name().equals(module.name())) {
						dependency.uses().stream()
							.filter(use -> use.usedClass().equals(type.name()))
							.forEach(use -> {
								users.add(dependency.moduleName());
								details.add(use.consumerClass() + " uses it");
							});
					}
				}
				if (users.isEmpty()) {
					continue;
				}
				details.add("Shared code that belongs to neither module: move it to a module of its own, or to the "
					+ "module that uses it.");
				all.add(new Finding(Level.HINT, Kind.EXPORTED_HELPER, "'" + module.name() + "' exports '"
					+ type.name() + "', a class of static helpers, which " + Text.quotedList(List.copyOf(users))
					+ " " + (users.size() == 1 ? "uses" : "use"), details, List.of(module.name())));
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

	/** The module whose classes break the rule. */
	private static String moduleOf(Invalid violation) {
		return switch (violation) {
			case NestedModuleViolation v -> v.nestedModuleName();
			case ExportViolation v -> v.consumerModuleName();
			case RequiresViolation v -> v.consumerModuleName();
			case DuplicateModuleNameViolation v -> v.moduleName();
			case UnknownRequiredModuleViolation v -> v.moduleName();
			case UnmanagedDependencyViolation v -> v.consumerModuleName();
		};
	}

	/** The lines of the verifier's message, without its heading. */
	private static List<String> detailsOf(Invalid violation) {
		return violation.formatError().lines().skip(1).map(String::strip).filter(line -> !line.isEmpty()).toList();
	}
}
