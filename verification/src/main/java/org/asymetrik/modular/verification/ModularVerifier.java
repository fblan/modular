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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.asymetrik.modular.api.Module;

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
	public sealed interface Invalid extends Result permits ModularVerifier.NestedModuleViolation, ModularVerifier.ExportViolation {}

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

	private record ModuleInfo(String name, String packageName, String[] exports) {
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

		// Find all modules
		List<ModuleInfo> modules = findAllModules(importedClasses);

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

		// Check for export violations
		Result exportCheck = checkExportViolations(importedClasses, modules);
		if (exportCheck instanceof Invalid) {
			return exportCheck;
		}

		return new Valid();
	}

	private Result checkExportViolations(JavaClasses classes, List<ModuleInfo> modules) {
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

				// Check all dependencies of this class
				for (JavaClass dependency : clazz.getDirectDependenciesFromSelf().stream()
						.map(dep -> dep.getTargetClass())
						.toList()) {

					String depPackage = dependency.getPackageName();

					// Find which module this dependency belongs to
					Optional<ModuleInfo> providerModule = findModuleForClass(depPackage, modules);

					if (providerModule.isPresent() && !providerModule.get().equals(consumerModule)) {
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

				modules.add(new ModuleInfo(moduleName, packageName, exports));
			}
		}

		return modules;
	}
}
