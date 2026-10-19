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
package org.asymetrik.modular.verification;

import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.asymetrik.modular.annotations.ExternalModule;
import org.asymetrik.modular.annotations.ExternalModules;
import org.asymetrik.modular.annotations.Module;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

public class ModularVerifier {

	/**
	 * Result of the modular verification process.
	 */
	public sealed interface Result permits ModularVerifier.Valid, ModularVerifier.Invalid{}

	/**
	 * Indicates that the modular verification passed without issues.
	 */
	public record Valid() implements Result{}

	/**
	 * Indicates that the modular verification failed due to violations.
	 */
	public sealed interface Invalid extends Result permits ModularVerifier.NestedModuleViolation, ModularVerifier.ExportViolation,
		ModularVerifier.RequiresViolation, ModularVerifier.DuplicateModuleNameViolation,
		ModularVerifier.UnknownRequiredModuleViolation, ModularVerifier.UnmanagedDependencyViolation {}

	/**
	 * Represents a violation where a module is nested within another module's package hierarchy.
	 */
	public record NestedModuleViolation(
		String parentModuleName,
		String nestedModuleName,
		String parentPackage,
		String nestedPackage,
		int nestingLevel
	) implements Invalid {

		/**
		 * Formats a detailed error message for the nested module violation.
		 *
		 * @return A formatted error message.
		 */
		public String formatError() {
			return String.format(
				"Module hierarchy violation detected:%n" +
				"  Parent: '%s' (%s)%n" +
				"  Nested: '%s' (%s)%n" +
				"  Level: %d (%s)%n" +
				"  Suggestion: Move '%s' module to %s",
				parentModuleName, parentPackage,
				nestedModuleName, nestedPackage,
				nestingLevel, nestingLevel == 1 ? "direct child" : "indirect descendant",
				nestedModuleName, suggestPeerPackage(nestedPackage, parentPackage)
			);
		}

		private static String suggestPeerPackage(String nestedPackage, String parentPackage) {
			// Extract the module name from nested package
			String[] parentParts = parentPackage.split("\\.");
			String[] nestedParts = nestedPackage.split("\\.");

			if (parentParts.length > 0 && nestedParts.length > parentParts.length) {
				// Suggest moving to same level as parent
				String[] suggestedParts = new String[parentParts.length];
				System.arraycopy(parentParts, 0, suggestedParts, 0, parentParts.length - 1);
				suggestedParts[parentParts.length - 1] = nestedParts[nestedParts.length - 1];
				return String.join(".", suggestedParts);
			}
			return nestedPackage;
		}
	}

	/**
	 * Represents a violation where a module uses a class from another module that is not exported.
	 */
	public record ExportViolation(
		String consumerModuleName,
		String consumerPackage,
		String consumerClass,
		String providerModuleName,
		String providerPackage,
		String violatingClass,
		String violatingClassPackage
	) implements Invalid {

		public String formatError() {
			return String.format(
				"Export violation detected:%n" +
				"  Consumer: '%s' (%s)%n" +
				"  Consumer class: %s%n" +
				"  Provider: '%s' (%s)%n" +
				"  Violating class: %s%n" +
				"  Package: %s (not exported)%n" +
				"  Suggestion: Module '%s' should export package '%s' or '%s' should not use this class",
				consumerModuleName, consumerPackage,
				consumerClass,
				providerModuleName, providerPackage,
				violatingClass, violatingClassPackage,
				providerModuleName, violatingClassPackage, consumerModuleName
			);
		}
	}

	/**
	 * Represents a violation where a module uses a class from another module it does not require.
	 */
	public record RequiresViolation(
		String consumerModuleName,
		String consumerPackage,
		String consumerClass,
		String providerModuleName,
		String providerPackage,
		String usedClass
	) implements Invalid {

		public String formatError() {
			return String.format(
				"Requires violation detected:%n" +
				"  Consumer: '%s' (%s)%n" +
				"  Consumer class: %s%n" +
				"  Provider: '%s' (%s)%n" +
				"  Used class: %s%n" +
				"  Suggestion: Module '%s' should add \"%s\" to its requires or should not use this class",
				consumerModuleName, consumerPackage,
				consumerClass,
				providerModuleName, providerPackage,
				usedClass,
				consumerModuleName, providerModuleName
			);
		}
	}

	/**
	 * Represents a violation where two modules share the same name, which makes {@code requires} ambiguous.
	 */
	public record DuplicateModuleNameViolation(
		String moduleName,
		String firstPackage,
		String secondPackage
	) implements Invalid {

		public String formatError() {
			return String.format(
				"Duplicate module name detected:%n" +
				"  Name: '%s'%n" +
				"  Declared by: %s%n" +
				"  And by: %s%n" +
				"  Suggestion: Rename one of the modules",
				moduleName, firstPackage, secondPackage
			);
		}
	}

	/**
	 * Represents a violation where a module requires a name that is neither a module, nor a virtual module, nor
	 * a JDK module, nor the JPMS module of a class used by the scanned classes.
	 */
	public record UnknownRequiredModuleViolation(
		String moduleName,
		String modulePackage,
		String requiredModuleName
	) implements Invalid {

		public String formatError() {
			return String.format(
				"Unknown required module detected:%n" +
				"  Module: '%s' (%s)%n" +
				"  Requires: '%s' (no module, virtual module, JDK module or used JPMS module has this name)%n" +
				"  Suggestion: Fix the name in requires, or remove it",
				moduleName, modulePackage, requiredModuleName
			);
		}
	}

	/**
	 * Represents a violation where a module uses a class that is neither in {@code java.*}, nor in a module,
	 * nor in a virtual module declared with {@link ExternalModule}.
	 */
	public record UnmanagedDependencyViolation(
		String consumerModuleName,
		String consumerPackage,
		String consumerClass,
		String usedClass,
		String usedClassPackage
	) implements Invalid {

		public String formatError() {
			return String.format(
				"Unmanaged dependency detected:%n" +
				"  Consumer: '%s' (%s)%n" +
				"  Consumer class: %s%n" +
				"  Used class: %s%n" +
				"  Package: %s (in no module, and only java.* can be used freely)%n" +
				"  Suggestion: Declare an @ExternalModule covering package '%s' and add it to the requires of '%s', " +
				"or '%s' should not use this class",
				consumerModuleName, consumerPackage,
				consumerClass,
				usedClass,
				usedClassPackage,
				usedClassPackage, consumerModuleName,
				consumerModuleName
			);
		}
	}

	/**
	 * A virtual module: named packages outside any module, each covering its sub-packages.
	 */
	private record ExternalModuleInfo(String name, String[] packages) {
		/** Length of the longest listed package covering {@code packageName}, or -1 if none does. */
		int coverageOf(String packageName) {
			return Arrays.stream(packages)
				.filter(p -> packageName.equals(p) || packageName.startsWith(p + "."))
				.mapToInt(String::length)
				.max()
				.orElse(-1);
		}
	}

	private record ModuleInfo(String name, String packageName, String[] exports, String[] requires) {
		boolean doesRequire(ModuleInfo other) {
			return Arrays.asList(requires).contains(other.name);
		}

		boolean isDescendantOf(ModuleInfo other) {
			return this.packageName.startsWith(other.packageName + ".");
		}

		int nestingLevelFrom(ModuleInfo parent) {
			if (!isDescendantOf(parent)) {
				return 0;
			}
			String relativePath = this.packageName.substring(parent.packageName.length() + 1);
			return relativePath.split("\\.").length;
		}
	}

	private final List<String> packages;

	/** JPMS module name by package, as found by {@link #findJpmsModuleName(JavaClass)}. */
	private final Map<String, Optional<String>> jpmsModuleByPackage = new HashMap<>();

	public ModularVerifier(final List<String> packages) {
		this.packages = packages;
	}
	public ModularVerifier(final String... packages) {
		this.packages = Arrays.asList(packages);
	}

	/**
	 * Verifies the modular structure of the imported packages.
	 *
	 * @return The result of the verification, either Valid or an Invalid violation.
	 */
	public Result verify(){
		JavaClasses importedClasses = new ClassFileImporter().importPackages(this.packages.toArray(new String[0]));

		// Find all modules and virtual modules
		List<ModuleInfo> modules = findAllModules(importedClasses);
		List<ExternalModuleInfo> externalModules = findAllExternalModules(importedClasses);

		// Check for nested module hierarchies
		for (ModuleInfo parent : modules) {
			for (ModuleInfo candidate : modules) {
				if (candidate.isDescendantOf(parent)) {
					return new NestedModuleViolation(
						parent.name(),
						candidate.name(),
						parent.packageName(),
						candidate.packageName(),
						candidate.nestingLevelFrom(parent)
					);
				}
			}
		}

		// Check that module names are unique and that required modules exist
		Set<String> usedJpmsModules = collectUsedJpmsModules(importedClasses, modules);
		Result namesCheck = checkModuleNames(modules, externalModules, usedJpmsModules);
		if (namesCheck instanceof Invalid) {
			return namesCheck;
		}

		// Check for requires and export violations
		Result dependencyCheck = checkDependencyViolations(importedClasses, modules, externalModules);
		if (dependencyCheck instanceof Invalid) {
			return dependencyCheck;
		}

		return new Valid();
	}

	private Result checkModuleNames(List<ModuleInfo> modules, List<ExternalModuleInfo> externalModules,
									Set<String> usedJpmsModules) {
		// Modules and virtual modules share one namespace, as both are referenced by name in requires
		Map<String, String> declarationByName = new HashMap<>();
		for (ModuleInfo module : modules) {
			String first = declarationByName.putIfAbsent(module.name(), module.packageName());
			if (first != null) {
				return new DuplicateModuleNameViolation(module.name(), first, module.packageName());
			}
		}
		for (ExternalModuleInfo external : externalModules) {
			String declaration = "external module " + Arrays.toString(external.packages());
			String first = declarationByName.putIfAbsent(external.name(), declaration);
			if (first != null) {
				return new DuplicateModuleNameViolation(external.name(), first, declaration);
			}
		}

		for (ModuleInfo module : modules) {
			for (String required : module.requires()) {
				if (!declarationByName.containsKey(required) && !usedJpmsModules.contains(required)
					&& ModuleFinder.ofSystem().find(required).isEmpty()) {
					return new UnknownRequiredModuleViolation(module.name(), module.packageName(), required);
				}
			}
		}

		return new Valid();
	}

	private Result checkDependencyViolations(JavaClasses classes, List<ModuleInfo> modules,
											 List<ExternalModuleInfo> externalModules) {
		// For each module, check its dependencies
		for (ModuleInfo consumerModule : modules) {
			// Get all classes in this module
			for (JavaClass clazz : classes) {
				String clazzPackage = clazz.getPackageName();

				// Skip if not in this module's package hierarchy
				if (!clazzPackage.equals(consumerModule.packageName()) &&
					!clazzPackage.startsWith(consumerModule.packageName() + ".")) {
					continue;
				}

				// package-info only carries the module declaration, it is not module code
				if (clazz.getSimpleName().equals("package-info")) {
					continue;
				}

				// Check all dependencies of this class
				for (JavaClass dependency : clazz.getDirectDependenciesFromSelf().stream()
						.map(dep -> dep.getTargetClass())
						.map(JavaClass::getBaseComponentType)
						.toList()) {

					if (dependency.isPrimitive()) {
						continue;
					}

					String depPackage = dependency.getPackageName();

					// java.* is the only package that can be used without being in a module
					if (depPackage.equals("java") || depPackage.startsWith("java.")) {
						continue;
					}

					// Find which module this dependency belongs to
					Optional<ModuleInfo> providerModule = findModuleForClass(depPackage, modules);

					if (providerModule.isEmpty()) {
						Result externalCheck = checkExternalDependency(
							consumerModule, clazz, dependency, depPackage, externalModules);
						if (externalCheck instanceof Invalid) {
							return externalCheck;
						}
						continue;
					}

					if (providerModule.isPresent() && !providerModule.get().equals(consumerModule)) {
						// Check that the consumer requires the provider module
						if (!consumerModule.doesRequire(providerModule.get())) {
							return new RequiresViolation(
								consumerModule.name(),
								consumerModule.packageName(),
								clazz.getFullName(),
								providerModule.get().name(),
								providerModule.get().packageName(),
								dependency.getFullName()
							);
						}

						// Check if the dependency's package is exported
						if (!isPackageExported(depPackage, providerModule.get())) {
							return new ExportViolation(
								consumerModule.name(),
								consumerModule.packageName(),
								clazz.getFullName(),
								providerModule.get().name(),
								providerModule.get().packageName(),
								dependency.getFullName(),
								depPackage
							);
						}
					}
				}
			}
		}

		return new Valid();
	}

	private Result checkExternalDependency(ModuleInfo consumerModule, JavaClass clazz, JavaClass dependency,
										   String depPackage, List<ExternalModuleInfo> externalModules) {
		// The most specific virtual module covering the package, if any
		Optional<ExternalModuleInfo> external = externalModules.stream()
			.filter(e -> e.coverageOf(depPackage) >= 0)
			.reduce((e1, e2) -> e1.coverageOf(depPackage) >= e2.coverageOf(depPackage) ? e1 : e2);

		if (external.isEmpty()) {
			Optional<String> jpmsModule = findJpmsModuleName(dependency);
			if (jpmsModule.isEmpty()) {
				return new UnmanagedDependencyViolation(
					consumerModule.name(),
					consumerModule.packageName(),
					clazz.getFullName(),
					dependency.getFullName(),
					depPackage
				);
			}
			if (!Arrays.asList(consumerModule.requires()).contains(jpmsModule.get())) {
				return new RequiresViolation(
					consumerModule.name(),
					consumerModule.packageName(),
					clazz.getFullName(),
					jpmsModule.get(),
					depPackage,
					dependency.getFullName()
				);
			}
			return new Valid();
		}

		// Everything in a virtual module is exported, it only has to be required
		if (!Arrays.asList(consumerModule.requires()).contains(external.get().name())) {
			return new RequiresViolation(
				consumerModule.name(),
				consumerModule.packageName(),
				clazz.getFullName(),
				external.get().name(),
				String.join(", ", external.get().packages()),
				dependency.getFullName()
			);
		}

		return new Valid();
	}

	/**
	 * JPMS modules of the classes outside {@code java.*} and outside the modules that the scanned classes use,
	 * so that requiring one of them is not reported as unknown.
	 */
	private Set<String> collectUsedJpmsModules(JavaClasses classes, List<ModuleInfo> modules) {
		Set<String> used = new HashSet<>();
		for (JavaClass clazz : classes) {
			clazz.getDirectDependenciesFromSelf().stream()
				.map(dep -> dep.getTargetClass().getBaseComponentType())
				.filter(dep -> !dep.isPrimitive())
				.filter(dep -> !dep.getPackageName().equals("java") && !dep.getPackageName().startsWith("java."))
				.filter(dep -> findModuleForClass(dep.getPackageName(), modules).isEmpty())
				.forEach(dep -> findJpmsModuleName(dep).ifPresent(used::add));
		}
		return used;
	}

	/**
	 * The JPMS module of a class, as JPMS itself would name it: its named module (JDK, module path), else the
	 * module of the jar or directory it is loaded from (its {@code module-info}, its {@code Automatic-Module-Name},
	 * or the name derived from the jar's file name). Empty if the class cannot be loaded or has no module name.
	 */
	private Optional<String> findJpmsModuleName(JavaClass javaClass) {
		return jpmsModuleByPackage.computeIfAbsent(javaClass.getPackageName(), p -> {
			try {
				Class<?> loaded = Class.forName(javaClass.getName(), false, ModularVerifier.class.getClassLoader());
				if (loaded.getModule().isNamed()) {
					return Optional.of(loaded.getModule().getName());
				}
				CodeSource codeSource = loaded.getProtectionDomain().getCodeSource();
				if (codeSource == null || codeSource.getLocation() == null) {
					return Optional.empty();
				}
				Path location = Path.of(codeSource.getLocation().toURI());
				return ModuleFinder.of(location).findAll().stream().findFirst()
					.map(ModuleReference::descriptor)
					.map(descriptor -> descriptor.name());
			} catch (ClassNotFoundException | LinkageError | URISyntaxException | FindException | SecurityException
					 | IllegalArgumentException notFound) {
				return Optional.empty();
			}
		});
	}

	private Optional<ModuleInfo> findModuleForClass(String classPackage, List<ModuleInfo> modules) {
		// Find the most specific module (longest matching package name)
		return modules.stream()
			.filter(module -> classPackage.equals(module.packageName()) ||
							 classPackage.startsWith(module.packageName() + "."))
			.reduce((m1, m2) -> m1.packageName().length() > m2.packageName().length() ? m1 : m2);
	}

	private boolean isPackageExported(String packageName, ModuleInfo module) {
		for (String export : module.exports()) {
			// Check if export matches exactly or is a pattern
			if (export.equals(packageName)) {
				return true;
			}
			// Handle "." which means the module's own package
			if (export.equals(".") && packageName.equals(module.packageName())) {
				return true;
			}
			// Handle wildcard patterns if needed
			if (export.endsWith("*") && packageName.startsWith(export.substring(0, export.length() - 1))) {
				return true;
			}
		}
		return false;
	}

	private List<ModuleInfo> findAllModules(JavaClasses classes) {
		List<ModuleInfo> modules = new ArrayList<>();

		for (JavaClass javaClass : classes) {
			// Look for package-info classes with @Module annotation
			if (javaClass.getSimpleName().equals("package-info") &&
				javaClass.isAnnotatedWith(Module.class)) {

				Module annotation = javaClass.getAnnotationOfType(Module.class);
				String moduleName = annotation.name();
				String packageName = javaClass.getPackageName();
				String[] exports = annotation.exports();
				String[] requires = annotation.requires();

				modules.add(new ModuleInfo(moduleName, packageName, exports, requires));
			}
		}

		return modules;
	}

	private List<ExternalModuleInfo> findAllExternalModules(JavaClasses classes) {
		List<ExternalModuleInfo> externalModules = new ArrayList<>();

		for (JavaClass javaClass : classes) {
			if (!javaClass.getSimpleName().equals("package-info")) {
				continue;
			}
			// A single @ExternalModule is stored as is, repeated ones inside their @ExternalModules container
			javaClass.tryGetAnnotationOfType(ExternalModule.class)
				.ifPresent(external -> externalModules.add(toExternalModuleInfo(external)));
			javaClass.tryGetAnnotationOfType(ExternalModules.class)
				.ifPresent(container -> Arrays.stream(container.value())
					.forEach(external -> externalModules.add(toExternalModuleInfo(external))));
		}

		return externalModules;
	}

	private static ExternalModuleInfo toExternalModuleInfo(ExternalModule annotation) {
		return new ExternalModuleInfo(annotation.name(), annotation.packages());
	}
}
