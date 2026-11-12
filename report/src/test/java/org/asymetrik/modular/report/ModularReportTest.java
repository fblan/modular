package org.asymetrik.modular.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.asymetrik.modular.verification.ModuleAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

class ModularReportTest {

	private static final String TESTAPP = "org.asymetrik.modular.report.testapp";
	private static final String ESCAPED_LEGACY = "legacy &lt;old&gt; &amp; &quot;co&quot;";
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:30:00Z"), ZoneOffset.UTC);

	// The fixtures are test classes: the analysis has to include them
	private final ModularReport report = ModularReport.of(ModuleAnalysis.of(TESTAPP)).withClock(CLOCK);
	private final String html = report.html();

	@Test
	void the_page_names_the_scanned_packages_and_its_generation_time() {
		assertThat(html).startsWith("<!DOCTYPE html>").contains("<title>Module report</title>")
			.contains("Package scanned: <code>" + TESTAPP + "</code>")
			.contains("Generated 2026-11-11 09:30 UTC by modular");
	}

	@Test
	void the_summary_counts_modules_types_dependencies_and_findings() {
		assertThat(html).contains("<dt>Modules</dt><dd class=\"value\">5</dd><dd class=\"sub\">and 1 virtual module</dd>")
			.contains("<dt>Types</dt><dd class=\"value\">7</dd>")
			.contains("<dt>Dependencies</dt><dd class=\"value\">6</dd><dd class=\"sub\">between modules, and 2 on what "
				+ "is outside</dd>")
			.contains("<dt>Findings</dt><dd class=\"value\">9</dd><dd class=\"sub\">1 error, 2 warnings, 6 hints</dd>")
			.contains("<p class=\"verdict error\">1 violation of the module rules.");
	}

	@Test
	void every_module_has_its_section() {
		assertThat(html)
			.contains("<article class=\"module warning\" id=\"module-1\">\n<header><h3>billing</h3><code>" + TESTAPP
				+ ".billing</code>")
			.contains("id=\"module-2\">\n<header><h3>catalog</h3>")
			.contains("id=\"module-3\">\n<header><h3>" + ESCAPED_LEGACY + "</h3>")
			.contains("id=\"module-4\">\n<header><h3>orders</h3>")
			.contains("id=\"module-5\">\n<header><h3>web</h3>")
			.doesNotContain("id=\"module-6\"");
	}

	@Test
	void every_finding_is_in_the_page() {
		assertThat(report.findings()).hasSize(9).allSatisfy(finding -> {
			assertThat(html).contains("<p class=\"what\">" + Text.escape(finding.title()) + "</p>");
			assertThat(finding.details()).allSatisfy(line -> assertThat(html).contains("<li>" + Text.escape(line)
				+ "</li>"));
		});
		assertThat(html).contains("<li class=\"finding error\" id=\"finding-1\"><span class=\"level\">error</span>")
			.contains("<li class=\"finding warning\" id=\"finding-2\"><span class=\"level\">warning</span>")
			.contains("<li class=\"finding hint\" id=\"finding-4\"><span class=\"level\">hint</span>")
			.contains("A hint can be a false alarm.");
	}

	@Test
	void names_are_escaped_everywhere() {
		assertThat(html).contains(ESCAPED_LEGACY).doesNotContain("<old>").doesNotContain("& \"co\"")
			.doesNotContain("&amp;lt;");
		// In an attribute and in the text of the diagram
		assertThat(html).contains("data-name=\"" + ESCAPED_LEGACY + "\"")
			.contains("text-anchor=\"middle\">" + ESCAPED_LEGACY + "</text>")
			.contains("<title>" + ESCAPED_LEGACY + " uses catalog: 1 use</title>");
	}

	@Test
	void the_diagram_is_well_formed_and_has_a_box_per_module_and_an_arrow_per_dependency() throws Exception {
		String svg = html.substring(html.indexOf("<svg"), html.indexOf("</svg>") + "</svg>".length());
		Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));

		assertThat(document.getElementsByTagName("rect").getLength()).isEqualTo(5);
		assertThat(document.getElementsByTagName("path").getLength()).isEqualTo(6);
		assertThat(document.getElementsByTagName("polygon").getLength()).isEqualTo(6);
		assertThat(document.getDocumentElement().getAttribute("aria-label"))
			.startsWith("Diagram of 5 modules and 6 dependencies, with 1 cycle.");
		assertThat(svg).contains("<title>web uses orders: 1 use</title>")
			.contains("<title>orders uses billing: 1 use, in a cycle</title>")
			.contains("<title>billing uses orders: 1 use, in a cycle</title>")
			.doesNotContain("money").doesNotContain("java.xml");
		assertThat(count(svg, "<g class=\"edge cycle\"")).isEqualTo(2);
		assertThat(count(svg, "<g class=\"edge\"")).isEqualTo(4);
	}

	@Test
	void the_matrix_gives_the_number_of_uses_between_modules() {
		String matrix = html.substring(html.indexOf("<table class=\"matrix\">"), html.indexOf("<section id=\"modules\">"));

		assertThat(matrix).contains("<td title=\"orders uses catalog: 1 use\">1</td>")
			.contains("<td title=\"web uses orders: 1 use\">1</td>")
			.contains("<td class=\"cycle\" title=\"orders uses billing: 1 use\">1</td>")
			.contains("<td class=\"cycle\" title=\"billing uses orders: 1 use\">1</td>")
			.contains("<td title=\"" + ESCAPED_LEGACY + " uses catalog: 1 use\">1</td>");
		assertThat(count(matrix, "<td class=\"self\">")).isEqualTo(5);
		assertThat(count(matrix, "<td></td>")).isEqualTo(5 * 5 - 5 - 6);
	}

	@Test
	void a_module_section_tells_what_it_exports_requires_uses_and_who_uses_it() {
		String orders = section("module-4");

		assertThat(orders).contains("<li><a href=\"#module-2\"><code>catalog</code></a></li>")
			.contains("<li><code>java.compiler</code> <span class=\"kind\">JPMS module</span> "
				+ "<span class=\"flag\">not seen in use</span></li>")
			.contains("<dt>Size</dt><dd>1 type, 2 methods, ")
			.contains("<li><code>" + TESTAPP + ".orders.Order</code> &rarr; <code>" + TESTAPP
				+ ".catalog.Product</code></li>")
			// Used by billing and by web
			.contains("<tr><th scope=\"row\"><a href=\"#module-1\"><code>billing</code></a></th>")
			.contains("<tr><th scope=\"row\"><a href=\"#module-5\"><code>web</code></a></th>");

		assertThat(section("module-2"))
			.contains("<li><code>.</code> <span class=\"kind\">its own package</span></li>")
			.contains("<li><code>" + TESTAPP + ".catalog.spi</code> <span class=\"flag\">not used by another module"
				+ "</span></li>")
			.contains("<dt>Exported</dt><dd>2 of 3 types, 1 internal</dd>")
			.contains("<p class=\"none\">Nothing outside <code>java.*</code>.</p>")
			.contains("<code>internal.ProductIndex</code></th><td><span class=\"kind\">internal</span></td>"
				+ "<td class=\"num\">3</td>");
		assertThat(section("module-3")).contains("<span class=\"flag error\">not required</span>")
			.contains("<p class=\"none\">No other module.</p>");
		assertThat(section("module-1")).contains("<code>money</code> <span class=\"kind\">virtual module</span>");
		assertThat(section("module-5")).contains("<dt>Exports</dt><dd>nothing</dd>")
			.contains("<code>java.xml</code> <span class=\"kind\">JPMS module</span></th>");
	}

	@Test
	void what_is_outside_the_modules_is_listed_with_its_users() {
		String outside = html.substring(html.indexOf("<section id=\"outside\">"), html.indexOf("<footer>"));

		assertThat(outside)
			.contains("<tr><th scope=\"row\"><code>money</code></th><td>virtual module</td><td><code>" + TESTAPP
				+ ".money</code></td><td><a href=\"#module-1\"><code>billing</code></a></td></tr>")
			.contains("<tr><th scope=\"row\"><code>java.xml</code></th><td>JPMS module</td><td></td><td>"
				+ "<a href=\"#module-5\"><code>web</code></a></td></tr>");
	}

	@Test
	void the_page_needs_nothing_else() {
		String withoutNamespace = html.replace("xmlns=\"http://www.w3.org/2000/svg\"", "");

		assertThat(withoutNamespace).doesNotContain("http://").doesNotContain("https://").doesNotContain("src=")
			.doesNotContain("<link").doesNotContain("@import").doesNotContain("url(");
		assertThat(html).contains("@media (prefers-color-scheme: dark)").contains("<style>").contains("<script>");
	}

	@Test
	void the_same_classes_give_the_same_page() {
		assertThat(ModularReport.of(ModuleAnalysis.of(TESTAPP)).withClock(CLOCK).html()).isEqualTo(html);

		Clock later = Clock.fixed(Instant.parse("2027-01-02T03:04:05Z"), ZoneOffset.UTC);
		List<String> now = html.lines().toList();
		List<String> then = report.withClock(later).html().lines().toList();
		assertThat(then).hasSameSizeAs(now);
		assertThat(then).filteredOn(line -> !now.contains(line)).singleElement().asString()
			.contains("Generated 2027-01-02 03:04 UTC");
	}

	@Test
	void sound_modules_are_said_to_be_sound() {
		String clean = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.cleanapp")).html();

		assertThat(clean)
			.contains("<p class=\"verdict\">No violation of the module rules, no cycle, no class outside the modules.</p>")
			.contains("<p class=\"none\">Nothing to report.</p>")
			.contains("<dt>Findings</dt><dd class=\"value\">0</dd><dd class=\"sub\">nothing to report</dd>")
			.contains("<title>alpha uses beta: 1 use</title>")
			.doesNotContain("class=\"edge cycle\"").doesNotContain("<section id=\"outside\">")
			.doesNotContain("A hint can be a false alarm.");
	}

	@Test
	void a_required_name_that_does_not_exist_is_flagged_in_the_module() {
		String broken = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.brokenapp")).html();

		assertThat(broken).contains("<li><code>ghost</code> <span class=\"flag error\">does not exist</span></li>")
			// One module: no matrix
			.doesNotContain("<section id=\"matrix\">");
	}

	@Test
	void packages_without_modules_give_a_page_that_says_so() {
		String empty = ModularReport.of("org.asymetrik.modular.report.nothing.here", "another.one").html();

		assertThat(empty).contains("Packages scanned: <code>org.asymetrik.modular.report.nothing.here</code>, "
				+ "<code>another.one</code>")
			.contains("No module found in the scanned packages.")
			.doesNotContain("<svg").doesNotContain("<section id=\"modules\">");
	}

	@Test
	void the_report_is_written_as_index_html(@TempDir Path directory) throws IOException {
		Path file = report.writeTo(directory.resolve("not/yet/there"));

		assertThat(file).isEqualTo(directory.resolve("not/yet/there/index.html"));
		assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(html);
	}

	@Test
	void main_writes_the_report_of_the_packages(@TempDir Path directory) throws IOException {
		// Test classes are left out, so this finds no module: the classes of the report itself are not modules
		ModularReport.main(new String[] {directory.toString(), "org.asymetrik.modular.report"});

		assertThat(Files.readString(directory.resolve("index.html"), StandardCharsets.UTF_8))
			.contains("Package scanned: <code>org.asymetrik.modular.report</code>")
			.contains("No module found in the scanned packages.");
	}

	@Test
	void main_takes_the_exclusions_and_the_page_names_them(@TempDir Path directory) throws IOException {
		ModularReport.main(new String[] {"--exclude-class=**$$*", "--exclude-class=**._*",
			"--exclude-use=io.example.Link", directory.toString(), "org.asymetrik.modular.report"});

		assertThat(Files.readString(directory.resolve("index.html"), StandardCharsets.UTF_8))
			.contains("<p class=\"scanned\">Left out: classes matching <code>**$$*</code>, <code>**._*</code>; "
				+ "uses of <code>io.example.Link</code>.</p>");
	}

	@Test
	void the_page_names_the_exclusions_of_one_kind_and_none_without_exclusions() {
		ModuleAnalysis.Exclusions classesOnly = new ModuleAnalysis.Exclusions(List.of("**.Stray"), List.of());
		ModuleAnalysis.Exclusions usesOnly = new ModuleAnalysis.Exclusions(List.of(), List.of("**.Stray"));

		assertThat(ModularReport.of(ModuleAnalysis.of(ModuleAnalysis.ClassScope.ALL_CLASSES, List.of(TESTAPP),
			classesOnly)).html()).contains("Left out: classes matching <code>**.Stray</code>.</p>");
		assertThat(ModularReport.of(ModuleAnalysis.of(ModuleAnalysis.ClassScope.ALL_CLASSES, List.of(TESTAPP),
			usesOnly)).html()).contains("Left out: uses of <code>**.Stray</code>.</p>");
		assertThat(html).doesNotContain("Left out:");
	}

	@Test
	void a_module_with_findings_has_the_color_of_the_most_serious_one() {
		// legacy breaks a rule, billing is in a cycle, catalog exports a package that no one uses
		assertThat(html).contains("<article class=\"module error\" id=\"module-3\">")
			.contains("<article class=\"module warning\" id=\"module-1\">")
			.contains("<article class=\"module hint\" id=\"module-2\">");

		// The modules table
		assertThat(html).contains("<tr class=\"warning\"><th scope=\"row\"><a href=\"#module-1\"><code>billing</code>"
				+ "</a> <span class=\"flag warning\">1 warning, 2 hints</span></th>")
			.contains("<tr class=\"error\"><th scope=\"row\"><a href=\"#module-3\"><code>" + ESCAPED_LEGACY
				+ "</code></a> <span class=\"flag error\">1 error, 1 hint</span></th>");

		// The diagram
		assertThat(html).contains("<g class=\"node warning\" data-module=")
			.contains("<g class=\"node error\" data-module=")
			.contains("<title>billing, 1 type, 1 warning, 2 hints</title>")
			.contains("A box with findings takes the color of the most serious one");

		// The section lists its findings, linked to the Findings section
		assertThat(html).contains("<li class=\"finding error\" id=\"finding-1\">");
		assertThat(section("module-3")).contains("<dt>Findings</dt><dd><ul class=\"about\"><li><span class=\"flag "
			+ "error\">error</span> <a href=\"#finding-1\">&#39;" + ESCAPED_LEGACY + "&#39; uses &#39;catalog&#39; "
			+ "without requiring it</a></li>");
	}

	@Test
	void the_modules_table_gives_the_numbers_of_each_module() {
		assertThat(html).contains("<thead><tr><th scope=\"col\">Module</th><th scope=\"col\" class=\"num\">Types</th>"
				+ "<th scope=\"col\" class=\"num\">Exported</th><th scope=\"col\" class=\"num\">Methods</th>")
			// catalog: 3 types, 2 of them exported
			.contains("<code>catalog</code></a> <span class=\"flag hint\">1 hint</span></th><td class=\"num\">3</td>"
				+ "<td class=\"num\">2</td>")
			.doesNotContain("class=\"bar\"");
	}

	@Test
	void a_page_without_findings_has_no_color() {
		String clean = ModularReport.of(ModuleAnalysis.of("org.asymetrik.modular.report.cleanapp")).html();

		assertThat(clean).doesNotContain("<ul class=\"about\">").doesNotContain("A box with findings")
			.doesNotContain("<tr class=").doesNotContain("class=\"node ").doesNotContain("class=\"module ");
	}

	private String section(String id) {
		int start = html.indexOf("\" id=\"" + id + "\">\n<header>");
		return html.substring(start, html.indexOf("</article>", start));
	}

	private static int count(String text, String part) {
		int count = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
			count++;
		}
		return count;
	}
}
