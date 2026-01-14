package org.asymetrik.modular.verification;

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
	}

}