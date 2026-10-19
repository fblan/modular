package org.asymetrik.modular.verification;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ModularVerifierTest {

	@Test
	void testing_an_isolated_module_should_return_success(){
		ModularVerifier modularVerifier = new ModularVerifier("org.asymetrik.modular.verification.testedmodules.validisolatedmod");
		ModularVerifier.Result result = modularVerifier.verify();
		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}
	@Test
	void testing_a_module_hierarchy_shoud_return_an_error(){
		ModularVerifier modularVerifier = new ModularVerifier("org.asymetrik.modular.verification.testedmodules.invalidhierarchy");
		ModularVerifier.Result result = modularVerifier.verify();
		Assertions.assertThat(result).isInstanceOf(ModularVerifier.NestedModuleViolation.class);
		ModularVerifier.NestedModuleViolation invalid = (ModularVerifier.NestedModuleViolation) result;

		// Enhanced assertions
		Assertions.assertThat(invalid.parentModuleName()).isEqualTo("testmodule");
		Assertions.assertThat(invalid.nestedModuleName()).isEqualTo("module");
		Assertions.assertThat(invalid.parentPackage())
			.isEqualTo("org.asymetrik.modular.verification.testedmodules.invalidhierarchy");
		Assertions.assertThat(invalid.nestedPackage())
			.isEqualTo("org.asymetrik.modular.verification.testedmodules.invalidhierarchy.submodule");
		Assertions.assertThat(invalid.nestingLevel()).isEqualTo(1);
	}

	@Test
	void testing_multiple_independent_modules_should_return_success() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.multimodule"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}

	@Test
	void testing_deeply_nested_module_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.deepnesting"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.NestedModuleViolation.class);
		ModularVerifier.NestedModuleViolation invalid = (ModularVerifier.NestedModuleViolation) result;

		Assertions.assertThat(invalid.parentModuleName()).isEqualTo("root");
		Assertions.assertThat(invalid.nestedModuleName()).isEqualTo("nested");
		Assertions.assertThat(invalid.nestingLevel()).isGreaterThan(1);
	}

	@Test
	void testing_module_with_regular_subpackages_should_return_success() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.withsubpackages"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}


	@Test
	void testing_scan_with_no_modules_should_return_success() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.nomodules"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}

	@Test
	void testing_module_using_exported_class_should_return_success() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.modulea",
			"org.asymetrik.modular.verification.testedmodules.modulevalidtest"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}

	@Test
	void testing_module_using_non_exported_class_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.modulea",
			"org.asymetrik.modular.verification.testedmodules.moduleusingnotexported"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.ExportViolation.class);
		ModularVerifier.ExportViolation violation = (ModularVerifier.ExportViolation) result;

		Assertions.assertThat(violation.consumerModuleName()).isEqualTo("moduleUsingNotExported");
		Assertions.assertThat(violation.providerModuleName()).isEqualTo("moduleA");
		Assertions.assertThat(violation.violatingClass()).contains("ClassNotExported");
		Assertions.assertThat(violation.consumerClass())
			.contains("IllegalConsumerClass");
	}

	@Test
	void testing_module_using_class_of_module_it_does_not_require_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.modulea",
			"org.asymetrik.modular.verification.testedmodules.modulenotrequiring"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.RequiresViolation.class);
		ModularVerifier.RequiresViolation violation = (ModularVerifier.RequiresViolation) result;

		Assertions.assertThat(violation.consumerModuleName()).isEqualTo("moduleNotRequiring");
		Assertions.assertThat(violation.providerModuleName()).isEqualTo("moduleA");
		Assertions.assertThat(violation.usedClass()).contains("ClassExported");
		Assertions.assertThat(violation.consumerClass()).contains("UndeclaredConsumerClass");
		Assertions.assertThat(violation.formatError()).contains("should add \"moduleA\" to its requires");
	}

	@Test
	void testing_module_requiring_unknown_module_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.unknownrequires"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.UnknownRequiredModuleViolation.class);
		ModularVerifier.UnknownRequiredModuleViolation violation = (ModularVerifier.UnknownRequiredModuleViolation) result;

		Assertions.assertThat(violation.moduleName()).isEqualTo("unknownRequires");
		Assertions.assertThat(violation.requiredModuleName()).isEqualTo("ghost");
	}

	@Test
	void testing_two_modules_with_same_name_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.duplicatenames"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.DuplicateModuleNameViolation.class);
		ModularVerifier.DuplicateModuleNameViolation violation = (ModularVerifier.DuplicateModuleNameViolation) result;

		Assertions.assertThat(violation.moduleName()).isEqualTo("dup");
		Assertions.assertThat(List.of(violation.firstPackage(), violation.secondPackage())).containsExactlyInAnyOrder(
			"org.asymetrik.modular.verification.testedmodules.duplicatenames.first",
			"org.asymetrik.modular.verification.testedmodules.duplicatenames.second"
		);
	}

	@Test
	void testing_module_using_class_of_required_external_module_should_return_success() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.externaldeclarations",
			"org.asymetrik.modular.verification.testedmodules.externalrequired"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
	}

	@Test
	void testing_module_using_class_of_external_module_it_does_not_require_should_return_error() {
		// Requires "annotation" (javax.annotation), but the more specific "processing" owns javax.annotation.processing
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.externaldeclarations",
			"org.asymetrik.modular.verification.testedmodules.externalnotrequired"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.RequiresViolation.class);
		ModularVerifier.RequiresViolation violation = (ModularVerifier.RequiresViolation) result;

		Assertions.assertThat(violation.consumerModuleName()).isEqualTo("externalnotrequired");
		Assertions.assertThat(violation.providerModuleName()).isEqualTo("processing");
		Assertions.assertThat(violation.usedClass()).isEqualTo("javax.annotation.processing.Processor");
	}

	@Test
	void testing_module_using_class_outside_java_and_any_module_should_return_error() {
		ModularVerifier modularVerifier = new ModularVerifier(
			"org.asymetrik.modular.verification.testedmodules.unmanaged"
		);
		ModularVerifier.Result result = modularVerifier.verify();

		Assertions.assertThat(result).isInstanceOf(ModularVerifier.UnmanagedDependencyViolation.class);
		ModularVerifier.UnmanagedDependencyViolation violation = (ModularVerifier.UnmanagedDependencyViolation) result;

		Assertions.assertThat(violation.consumerModuleName()).isEqualTo("unmanaged");
		Assertions.assertThat(violation.consumerClass()).contains("UnmanagedUser");
		Assertions.assertThat(violation.usedClass()).isEqualTo("javax.annotation.processing.Processor");
		Assertions.assertThat(violation.usedClassPackage()).isEqualTo("javax.annotation.processing");
		Assertions.assertThat(violation.formatError()).contains("Declare an @ExternalModule covering package 'javax.annotation.processing'");
	}

}