package org.asymetrik.modular.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;

import org.asymetrik.modular.report.Finding.Kind;
import org.asymetrik.modular.report.Finding.Level;
import org.asymetrik.modular.verification.ModuleAnalysis;
import org.junit.jupiter.api.Test;

class FindingsTest {

	private static final String TESTAPP = "org.asymetrik.modular.report.testapp";
	private static final String DESIGNAPP = "org.asymetrik.modular.report.designapp";
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
				"'" + LEGACY + "' exports its own package, but no other module uses it"),
			tuple(Level.HINT, Kind.LEAKY_API,
				"'orders' uses exported types of 'billing' that use 'money', without requiring 'money'"),
			tuple(Level.HINT, Kind.LEAKY_API,
				"'web' uses exported types of 'orders' that use 'billing', without requiring 'billing'"),
			tuple(Level.HINT, Kind.LEAKY_API,
				"'billing' uses exported types of 'orders' that use 'catalog', without requiring 'catalog'"));
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

	@Test
	void a_used_jdk_module_and_java_base_get_no_hint() {
		ModularReport jdk = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.jdkapp"));

		// java.net.http is used, java.base is used by every class, java.management is not used
		assertThat(jdk.findings()).extracting(Finding::level, Finding::kind, Finding::title).containsExactly(
			tuple(Level.HINT, Kind.REQUIRES_NOT_USED, "'jdkapp' requires 'java.management', but no use of it was found"));
	}

	@Test
	void an_excluded_class_gives_no_finding() {
		// LegacyReport is the class behind the violation, Stray the class outside the modules
		ModularReport excluded = ModularReport.of(ModuleAnalysis.of(ModuleAnalysis.ClassScope.ALL_CLASSES,
			List.of(TESTAPP), new ModuleAnalysis.Exclusions(List.of("**.LegacyReport", "**.Stray"), List.of())));

		assertThat(excluded.findings()).extracting(Finding::kind)
			.doesNotContain(Kind.VIOLATION, Kind.CLASSES_OUTSIDE_MODULES)
			.contains(Kind.CYCLE);
	}

	@Test
	void design_hints_point_at_large_types_large_methods_leaky_apis_borrowed_exceptions_and_exported_helpers() {
		ModularReport design = ModularReport.of(ModuleAnalysis.of(DESIGNAPP));

		// Medium has 20 public methods; Lengthy.atTheLimit has 75 lines of code; informed requires base and only
		// catches CoreException. CoreException is @Audited, of marks, which client doesn't require: an annotation is
		// no leak
		assertThat(design.findings()).extracting(Finding::level, Finding::kind, Finding::title).containsExactly(
			tuple(Level.HINT, Kind.LARGE_TYPE,
				"'" + DESIGNAPP + ".core.Big', in 'core', has 21 public methods, more than 20"),
			tuple(Level.HINT, Kind.LARGE_METHOD,
				"'" + DESIGNAPP + ".core.Lengthy.tooLong()', in 'core', has 76 lines of code, more than 75"),
			tuple(Level.HINT, Kind.LEAKY_API,
				"'client' uses exported types of 'core' that use 'base', without requiring 'base'"),
			tuple(Level.HINT, Kind.BORROWED_EXCEPTION, "'client' creates exceptions of 'core'"),
			tuple(Level.HINT, Kind.EXPORTED_HELPER, "'core' exports '" + DESIGNAPP
				+ ".core.Texts', a class of static helpers, which 'client' uses"));
	}

	@Test
	void a_design_hint_names_the_classes_behind_it_and_what_to_do() {
		List<Finding> findings = ModularReport.of(ModuleAnalysis.of(DESIGNAPP)).findings();

		assertThat(findings.get(0).details()).containsExactly(
			"A type with many public methods usually does several jobs: split it by job.");
		assertThat(findings.get(1).details()).containsExactly(
			"A long method usually does several things: split it into methods that each do one.");
		findings = findings.subList(1, findings.size());
		assertThat(findings.get(1).details()).containsExactly(
			DESIGNAPP + ".core.CoreView uses " + DESIGNAPP + ".base.BaseId",
			DESIGNAPP + ".client.Client uses " + DESIGNAPP + ".core.CoreView",
			"Keep 'base' out of the exported types of 'core', or require 'base' where they are used.");
		assertThat(findings.get(2).details()).containsExactly(
			DESIGNAPP + ".client.Client creates " + DESIGNAPP + ".core.CoreException",
			"Its errors then depend on how 'core' handles them: give 'client' exceptions of its own. "
				+ "Catching them is fine.");
		assertThat(findings.get(3).details()).containsExactly(
			DESIGNAPP + ".client.Client uses it",
			"Shared code that belongs to neither module: move it to a module of its own, or to the module that uses it.");
	}

	@Test
	void a_module_with_more_than_50_types_is_a_warning() {
		List<Finding> findings = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.sizeapp"))
			.findings();

		// limit has exactly 50 types
		assertThat(findings).extracting(Finding::level, Finding::kind, Finding::title, Finding::modules)
			.containsExactly(tuple(Level.WARNING, Kind.LARGE_MODULE, "'large' has 51 types, more than 50",
				List.of("large")));
		// Part01 has 3 public methods and 4 lines (its constructor's and one per method), Part02 to Part20 one public
		// method and 2 lines each, Part21 to Part51 a constructor of one line each
		assertThat(findings.get(0).details()).containsExactly("22 public methods, 73 lines of code",
			"A module with many types usually holds several parts of the domain: split it by part.");
	}

	@Test
	void a_finding_names_the_modules_to_look_at() {
		assertThat(report.findings()).extracting(Finding::kind, Finding::modules).containsExactly(
			tuple(Kind.VIOLATION, List.of(LEGACY)),
			tuple(Kind.CYCLE, List.of("billing", "orders")),
			tuple(Kind.CLASSES_OUTSIDE_MODULES, List.of()),
			tuple(Kind.REQUIRES_NOT_USED, List.of("orders")),
			tuple(Kind.EXPORT_NOT_USED, List.of("catalog")),
			tuple(Kind.EXPORT_NOT_USED, List.of(LEGACY)),
			// The module that leaks, then the ones that receive
			tuple(Kind.LEAKY_API, List.of("billing", "orders")),
			tuple(Kind.LEAKY_API, List.of("orders", "web")),
			tuple(Kind.LEAKY_API, List.of("orders", "billing")));

		List<Finding> design = ModularReport.of(ModuleAnalysis.of(DESIGNAPP)).findings();
		assertThat(design).filteredOn(finding -> finding.kind() != Kind.LEAKY_API)
			.allSatisfy(finding -> assertThat(finding.modules()).hasSize(1));
	}

	@Test
	void a_module_takes_the_most_serious_level_of_its_findings() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTAPP);
		Findings findings = new Findings(analysis, ModuleGraph.of(analysis));

		assertThat(findings.levelOfModules()).containsOnly(
			Map.entry(LEGACY, Level.ERROR),
			Map.entry("billing", Level.WARNING),
			Map.entry("orders", Level.WARNING),
			Map.entry("catalog", Level.HINT),
			Map.entry("web", Level.HINT));
		assertThat(findings.about("billing")).extracting(Finding::kind)
			.containsExactly(Kind.CYCLE, Kind.LEAKY_API, Kind.LEAKY_API);
	}

	@Test
	void a_finding_about_no_module_says_so() {
		assertThat(new Finding(Level.HINT, Kind.LARGE_TYPE, "title", List.of()).modules()).isEmpty();
	}
}
