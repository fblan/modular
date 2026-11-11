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

import java.util.Arrays;
import java.util.List;

import org.asymetrik.modular.annotations.ExternalModule;

/**
 * Verifies that the modules of some packages follow the module rules. It reports the first violation of
 * {@link ModuleAnalysis#violations()}; use {@link ModuleAnalysis} to get all of them.
 */
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
		ModularVerifier.UnknownRequiredModuleViolation, ModularVerifier.UnmanagedDependencyViolation {

		/**
		 * Describes the violation for a person: what was found, where, and how to fix it.
		 *
		 * @return A message on several lines.
		 */
		String formatError();
	}

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
		List<Invalid> violations = ModuleAnalysis.of(this.packages).violations();
		return violations.isEmpty() ? new Valid() : violations.get(0);
	}
}
