package org.asymetrik.modular.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import org.asymetrik.modular.verification.ModuleAnalysis.ClassScope;
import org.asymetrik.modular.verification.ModuleAnalysis.ClassUse;
import org.asymetrik.modular.verification.ModuleAnalysis.Dependency;
import org.asymetrik.modular.verification.ModuleAnalysis.Exclusions;
import org.asymetrik.modular.verification.ModuleAnalysis.ExternalModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.ModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.Target;
import org.asymetrik.modular.verification.ModuleAnalysis.TargetKind;
import org.asymetrik.modular.verification.ModuleAnalysis.TypeDescription;
import org.asymetrik.modular.verification.testedmodules.shop.catalog.internal.ProductIndex;
import org.junit.jupiter.api.Test;

class ModuleAnalysisTest {

	private static final String TESTED = "org.asymetrik.modular.verification.testedmodules";
	private static final String SHOP = TESTED + ".shop";
	private static final String BROKEN = TESTED + ".broken";
	private static final String EXCLUSIONS = TESTED + ".exclusions";
	private static final String TRAITS = TESTED + ".traits";
	private static final Exclusions GENERATED_AND_WOVEN = new Exclusions(List.of("**$$Adapter", "**._*"),
		List.of("**.framework.Link"));

	@Test
	void modules_are_described_sorted_by_name() {
		ModuleAnalysis analysis = ModuleAnalysis.of(SHOP);

		assertThat(analysis.packages()).containsExactly(SHOP);
		assertThat(analysis.modules()).extracting(ModuleDescription::name)
			.containsExactly("shop-billing", "shop-catalog", "shop-orders");

		ModuleDescription orders = analysis.modules().get(2);
		assertThat(orders.packageName()).isEqualTo(SHOP + ".orders");
		assertThat(orders.exports()).containsExactly(".");
		assertThat(orders.requires()).containsExactly("shop-catalog", "shop-billing", "java.compiler");
		assertThat(orders.types()).extracting(TypeDescription::name).containsExactly(SHOP + ".orders.Order");
	}

	@Test
	void types_tell_their_package_and_whether_it_is_exported() {
		ModuleDescription catalog = ModuleAnalysis.of(SHOP).modules().get(1);

		// package-info is not a type of the module
		assertThat(catalog.types()).extracting(TypeDescription::name, TypeDescription::packageName,
				TypeDescription::exported)
			.containsExactly(
				tuple(SHOP + ".catalog.Product", SHOP + ".catalog", true),
				tuple(SHOP + ".catalog.internal.ProductIndex",
					SHOP + ".catalog.internal", false),
				tuple(SHOP + ".catalog.spi.ProductSource", SHOP + ".catalog.spi",
					true));
	}

	@Test
	void nested_classes_are_counted_with_their_top_level_type() throws IOException, URISyntaxException {
		ModuleDescription catalog = ModuleAnalysis.of(SHOP).modules().get(1);
		TypeDescription product = catalog.types().get(0);
		TypeDescription index = catalog.types().get(1);

		// Constructors and the lambda's synthetic method are not counted
		assertThat(product.methods()).isEqualTo(2);
		// contains and add, plus Entry.matches
		assertThat(index.methods()).isEqualTo(3);

		Path indexClass = Path.of(ProductIndex.class.getResource("ProductIndex.class").toURI());
		Path entryClass = indexClass.resolveSibling("ProductIndex$Entry.class");
		assertThat(index.classFileBytes()).isEqualTo(OptionalLong.of(Files.size(indexClass) + Files.size(entryClass)));
	}

	@Test
	void dependencies_are_what_modules_really_use() {
		ModuleAnalysis analysis = ModuleAnalysis.of(SHOP);

		// java.util and the uses inside a module are left out; java.compiler is required but not used
		assertThat(analysis.dependencies()).containsExactly(
			new Dependency("shop-billing", new Target(TargetKind.MODULE, "shop-orders"),
				List.of(new ClassUse(SHOP + ".billing.Invoice", SHOP + ".orders.Order"))),
			new Dependency("shop-orders", new Target(TargetKind.MODULE, "shop-billing"),
				List.of(new ClassUse(SHOP + ".orders.Order", SHOP + ".billing.Invoice", true))),
			new Dependency("shop-orders", new Target(TargetKind.MODULE, "shop-catalog"),
				List.of(new ClassUse(SHOP + ".orders.Order", SHOP + ".catalog.Product"))));
	}

	@Test
	void a_cycle_between_modules_is_not_a_violation() {
		assertThat(ModuleAnalysis.of(SHOP).violations()).isEmpty();
		assertThat(new ModularVerifier(SHOP).verify()).isInstanceOf(ModularVerifier.Valid.class);
	}

	@Test
	void classes_outside_any_module_are_listed() {
		assertThat(ModuleAnalysis.of(SHOP).unassignedClasses()).containsExactly(SHOP + ".Unassigned");
	}

	@Test
	void every_violation_is_reported_names_before_dependencies() {
		ModuleAnalysis analysis = ModuleAnalysis.of(BROKEN);

		assertThat(analysis.violations()).containsExactly(
			new ModularVerifier.UnknownRequiredModuleViolation("broken-consumer", BROKEN + ".consumer", "ghost"),
			// ApiUser uses Api twice: reported once
			new ModularVerifier.RequiresViolation("broken-consumer", BROKEN + ".consumer",
				BROKEN + ".consumer.ApiUser", "broken-provider", BROKEN + ".provider", BROKEN + ".provider.Api"),
			new ModularVerifier.ExportViolation("broken-other", BROKEN + ".other", BROKEN + ".other.SecretUser",
				"broken-provider", BROKEN + ".provider", BROKEN + ".provider.hidden.Secret",
				BROKEN + ".provider.hidden"));
	}

	@Test
	void every_violation_can_be_formatted() {
		assertThat(ModuleAnalysis.of(BROKEN).violations()).extracting(ModularVerifier.Invalid::formatError)
			.satisfiesExactly(
				message -> assertThat(message).startsWith("Unknown required module detected:").contains("'ghost'"),
				message -> assertThat(message).startsWith("Requires violation detected:"),
				message -> assertThat(message).startsWith("Export violation detected:"));
	}

	@Test
	void the_verifier_returns_the_first_violation() {
		assertThat(new ModularVerifier(BROKEN).verify()).isEqualTo(ModuleAnalysis.of(BROKEN).violations().get(0));
	}

	@Test
	void a_forbidden_use_is_still_a_dependency() {
		assertThat(ModuleAnalysis.of(BROKEN).dependencies()).containsExactly(
			new Dependency("broken-consumer", new Target(TargetKind.MODULE, "broken-provider"),
				List.of(new ClassUse(BROKEN + ".consumer.ApiUser", BROKEN + ".provider.Api"))),
			new Dependency("broken-other", new Target(TargetKind.MODULE, "broken-provider"),
				List.of(new ClassUse(BROKEN + ".other.SecretUser", BROKEN + ".provider.hidden.Secret"))));
	}

	@Test
	void nested_modules_are_all_reported() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".deepnesting");

		assertThat(analysis.violations()).isNotEmpty().allSatisfy(violation ->
			assertThat(violation).isInstanceOf(ModularVerifier.NestedModuleViolation.class));
	}

	@Test
	void a_virtual_module_is_a_target() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".externaldeclarations", TESTED + ".externalrequired");

		assertThat(analysis.externalModules()).containsExactly(
			new ExternalModuleDescription("annotation", List.of("javax.annotation")),
			new ExternalModuleDescription("processing", List.of("javax.annotation.processing")));
		// The most specific virtual module owns the package
		assertThat(analysis.dependencies()).containsExactly(
			new Dependency("externalrequired", new Target(TargetKind.EXTERNAL_MODULE, "processing"),
				List.of(new ClassUse(TESTED + ".externalrequired.ProcessorUser",
					"javax.annotation.processing.Processor"))));
		assertThat(analysis.violations()).isEmpty();
	}

	@Test
	void a_jpms_module_is_a_target() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".jpmsrequired");

		assertThat(analysis.dependencies()).extracting(Dependency::moduleName, Dependency::target)
			.containsExactly(
				tuple("jpmsrequired", new Target(TargetKind.JPMS_MODULE, "java.compiler")),
				tuple("jpmsrequired",
					new Target(TargetKind.JPMS_MODULE, "org.junit.jupiter.api")));
		assertThat(analysis.violations()).isEmpty();
	}

	@Test
	void an_unmanaged_package_is_a_target() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".unmanaged");

		assertThat(analysis.dependencies()).containsExactly(
			new Dependency("unmanaged", new Target(TargetKind.UNMANAGED, TESTED + ".nomodules"),
				List.of(new ClassUse(TESTED + ".unmanaged.UnmanagedUser", TESTED + ".nomodules.RegularClass"))));
		assertThat(analysis.violations()).hasSize(1).first()
			.isInstanceOf(ModularVerifier.UnmanagedDependencyViolation.class);
	}

	@Test
	void test_classes_can_be_left_out() {
		// The fixtures are test classes
		ModuleAnalysis analysis = ModuleAnalysis.of(ClassScope.WITHOUT_TESTS, List.of(SHOP));

		assertThat(analysis.modules()).isEmpty();
		assertThat(analysis.unassignedClasses()).isEmpty();
		assertThat(ModuleAnalysis.of(ClassScope.ALL_CLASSES, List.of(SHOP)).modules()).hasSize(3);
	}

	@Test
	void packages_without_modules_have_only_unassigned_classes() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".nomodules");

		assertThat(analysis.modules()).isEmpty();
		assertThat(analysis.dependencies()).isEmpty();
		assertThat(analysis.violations()).isEmpty();
		assertThat(analysis.unassignedClasses()).containsExactly(TESTED + ".nomodules.RegularClass");
	}

	@Test
	void generated_and_woven_code_breaks_rules_unless_excluded() {
		ModuleAnalysis analysis = ModuleAnalysis.of(EXCLUSIONS);

		assertThat(analysis.exclusions()).isEqualTo(Exclusions.NONE);
		String web = EXCLUSIONS + ".web.";
		assertThat(analysis.violations()).containsExactlyInAnyOrder(
			new ModularVerifier.UnmanagedDependencyViolation("excl-web", EXCLUSIONS + ".web", web + "Shop",
				EXCLUSIONS + ".framework.Link", EXCLUSIONS + ".framework"),
			new ModularVerifier.RequiresViolation("excl-web", EXCLUSIONS + ".web", web + "Shop$$Adapter", "excl-store",
				EXCLUSIONS + ".store", EXCLUSIONS + ".store.Store"),
			new ModularVerifier.RequiresViolation("excl-web", EXCLUSIONS + ".web", web + "Shop$$Adapter$Route",
				"excl-store", EXCLUSIONS + ".store", EXCLUSIONS + ".store.Store"),
			new ModularVerifier.UnmanagedDependencyViolation("excl-web", EXCLUSIONS + ".web", web + "_Components",
				EXCLUSIONS + ".framework.Link", EXCLUSIONS + ".framework"));
		assertThat(analysis.unassignedClasses()).contains(EXCLUSIONS + ".Orphan$$Adapter");
	}

	@Test
	void excluded_classes_are_left_out_entirely() {
		ModuleAnalysis analysis = ModuleAnalysis.of(ClassScope.ALL_CLASSES, List.of(EXCLUSIONS), GENERATED_AND_WOVEN);

		assertThat(analysis.exclusions()).isEqualTo(GENERATED_AND_WOVEN);
		assertThat(analysis.violations()).isEmpty();
		// Shop$$Adapter$Route is left out with its top-level class, though the pattern does not match it
		assertThat(analysis.modules()).filteredOn(module -> module.name().equals("excl-web")).singleElement()
			.satisfies(web -> assertThat(web.types()).extracting(TypeDescription::name)
				.containsExactly(EXCLUSIONS + ".web.Shop"));
		assertThat(analysis.dependencies()).isEmpty();
		assertThat(analysis.unassignedClasses()).containsExactly(EXCLUSIONS + ".framework.Link");
	}

	@Test
	void an_excluded_use_is_no_dependency() {
		ModuleAnalysis analysis = ModuleAnalysis.of(ClassScope.ALL_CLASSES, List.of(EXCLUSIONS),
			new Exclusions(List.of(), List.of("**.framework.Link")));

		assertThat(analysis.dependencies()).extracting(Dependency::moduleName, Dependency::target)
			.containsExactly(tuple("excl-web", new Target(TargetKind.MODULE, "excl-store")));
	}

	@Test
	void patterns_match_whole_class_names() {
		assertThat(Exclusions.toRegex("**$$*").matcher("com.example.web.Shop$$Adapter").matches()).isTrue();
		assertThat(Exclusions.toRegex("**$$*").matcher("com.example.web.Shop").matches()).isFalse();
		// * stops at a dot, ** does not
		assertThat(Exclusions.toRegex("com.*.Shop").matcher("com.example.Shop").matches()).isTrue();
		assertThat(Exclusions.toRegex("com.*.Shop").matcher("com.example.web.Shop").matches()).isFalse();
		assertThat(Exclusions.toRegex("com.**.Shop").matcher("com.example.web.Shop").matches()).isTrue();
		// Everything else is literal
		assertThat(Exclusions.toRegex("com.example.Shop").matcher("comXexampleXShop").matches()).isFalse();
		assertThat(Exclusions.toRegex("**RepositoryImpl").matcher("com.example.ShopRepositoryImpl").matches()).isTrue();
		assertThat(Exclusions.toRegex("**RepositoryImpl").matcher("com.example.ShopRepositoryImpl2").matches()).isFalse();
	}

	@Test
	void a_blank_pattern_is_rejected() {
		assertThatThrownBy(() -> new Exclusions(List.of(" "), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Exclusions(List.of(), List.of("")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(Exclusions.NONE.isEmpty()).isTrue();
		assertThat(GENERATED_AND_WOVEN.isEmpty()).isFalse();
	}

	@Test
	void a_jdk_module_other_than_java_base_is_a_dependency_that_breaks_no_rule() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TESTED + ".jdkuse");

		// java.util.List is in java.base: left out
		assertThat(analysis.dependencies()).containsExactly(
			new Dependency("jdkuse", new Target(TargetKind.JPMS_MODULE, "java.net.http"),
				List.of(new ClassUse(TESTED + ".jdkuse.Client", "java.net.http.HttpClient"))));
		assertThat(analysis.violations()).isEmpty();
	}

	@Test
	void a_type_says_whether_it_is_an_exception_or_a_class_of_static_helpers() {
		ModuleDescription provider = ModuleAnalysis.of(TRAITS).modules().get(1);

		assertThat(provider.name()).isEqualTo("traits-provider");
		assertThat(provider.types()).extracting(TypeDescription::name, TypeDescription::exception,
			TypeDescription::staticHelper).containsExactly(
			tuple(TRAITS + ".provider.Constants", false, false),
			tuple(TRAITS + ".provider.Factory", false, false),
			tuple(TRAITS + ".provider.Helpers", false, true),
			tuple(TRAITS + ".provider.Kind", false, false),
			tuple(TRAITS + ".provider.Mixed", false, false),
			tuple(TRAITS + ".provider.OpenHelper", false, false),
			tuple(TRAITS + ".provider.Point", false, false),
			tuple(TRAITS + ".provider.ProviderException", true, false),
			tuple(TRAITS + ".provider.Shape", false, false));
	}

	@Test
	void a_use_says_whether_it_creates_instances() {
		ModuleAnalysis analysis = ModuleAnalysis.of(TRAITS);

		// A nested class creates for its top-level type; super(...) and catch create nothing
		assertThat(analysis.dependencies()).containsExactly(
			new Dependency("traits-consumer", new Target(TargetKind.MODULE, "traits-provider"), List.of(
				new ClassUse(TRAITS + ".consumer.Catcher", TRAITS + ".provider.Helpers"),
				new ClassUse(TRAITS + ".consumer.Catcher", TRAITS + ".provider.ProviderException"),
				new ClassUse(TRAITS + ".consumer.NestedThrower", TRAITS + ".provider.ProviderException", true),
				new ClassUse(TRAITS + ".consumer.OwnException", TRAITS + ".provider.ProviderException"),
				new ClassUse(TRAITS + ".consumer.Thrower", TRAITS + ".provider.ProviderException", true))));
	}

	@Test
	void the_shorter_constructors_mean_no_trait_and_no_instance_created() {
		assertThat(new TypeDescription("a.B", "a", true, 1, OptionalLong.empty()))
			.isEqualTo(new TypeDescription("a.B", "a", true, 1, OptionalLong.empty(), false, false));
		assertThat(new ClassUse("a.B", "c.D")).isEqualTo(new ClassUse("a.B", "c.D", false));
	}
}
