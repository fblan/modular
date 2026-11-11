package org.asymetrik.modular.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.asymetrik.modular.report.Finding.Kind;
import org.asymetrik.modular.report.Finding.Level;
import org.asymetrik.modular.verification.ModuleAnalysis;
import org.junit.jupiter.api.Test;

class FindingsTest {

	private static final String TESTAPP = "org.asymetrik.modular.report.testapp";
	private static final String LEGACY = "legacy <old> & \"co\"";

	// The fixtures are test classes: the analysis has to include them
	private final ModularReport report = ModularReport.of(ModuleAnalysis.of(TESTAPP));

	@Test
	void findings_are_errors_then_warnings_then_hints() {
		assertThat(report.findings()).extracting(Finding::level, Finding::kind, Finding::title).containsExactly(
			tuple(Level.ERROR, Kind.VIOLATION, "'" + LEGACY + "' uses 'catalog' without requiring it"),
			tuple(Level.WARNING, Kind.CYCLE, "Cycle between 'billing' and 'orders'"),
			tuple(Level.WARNING, Kind.CLASSES_OUTSIDE_MODULES, "1 class is in no module"),
			tuple(Level.HINT, Kind.REQUIRES_NOT_USED, "'orders' requires 'java.compiler', but no use of it was found"),
			tuple(Level.HINT, Kind.EXPORT_NOT_USED,
				"'catalog' exports '" + TESTAPP + ".catalog.spi', but no other module uses it"),
			tuple(Level.HINT, Kind.EXPORT_NOT_USED,
				"'" + LEGACY + "' exports its own package, but no other module uses it"));
	}

	@Test
	void a_violation_keeps_the_lines_of_the_verifier() {
		Finding violation = report.findings().get(0);

		assertThat(violation.details()).isNotEmpty().allSatisfy(line -> assertThat(line).isEqualTo(line.strip()));
		assertThat(violation.details()).anySatisfy(line -> assertThat(line).contains(TESTAPP + ".legacy.LegacyReport"));
		assertThat(violation.details()).anySatisfy(line -> assertThat(line).contains(TESTAPP + ".catalog.Product"));
		assertThat(violation.details()).noneMatch(line -> line.contains("detected:"));
	}

	@Test
	void a_cycle_lists_the_uses_that_make_it() {
		Finding cycle = report.findings().get(1);

		assertThat(cycle.details()).containsExactly("'billing' uses 'orders' (1 use)", "'orders' uses 'billing' (1 use)");
	}

	@Test
	void classes_outside_the_modules_are_named_unless_a_virtual_module_covers_them() {
		Finding outside = report.findings().get(2);

		// money.Amount is in the virtual module "money"
		assertThat(outside.details()).containsExactly(TESTAPP + ".Stray");
	}

	@Test
	void a_required_name_that_does_not_exist_is_a_violation_and_not_also_a_hint() {
		ModularReport broken = ModularReport.of(ModuleAnalysis.of(
			"org.asymetrik.modular.report.brokenapp"));

		assertThat(broken.findings()).extracting(Finding::level, Finding::title).containsExactly(
			tuple(Level.ERROR, "'lonely' requires 'ghost', which does not exist"));
	}

	@Test
	void sound_modules_have_no_finding() {
		ModularReport clean = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.cleanapp"));

		assertThat(clean.findings()).isEmpty();
	}

	@Test
	void the_findings_cannot_be_changed() {
		List<Finding> findings = report.findings();

		assertThat(findings).isUnmodifiable();
		assertThat(findings.get(0).details()).isUnmodifiable();
	}

	@Test
	void test_classes_are_left_out_by_default() {
		// The fixtures are test classes
		assertThat(ModularReport.of(TESTAPP).findings()).isEmpty();
		assertThat(ModularReport.of(List.of(TESTAPP)).html()).contains("No module found in the scanned packages.");
	}
}
