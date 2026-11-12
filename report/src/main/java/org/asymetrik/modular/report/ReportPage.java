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

import static org.asymetrik.modular.report.Text.count;
import static org.asymetrik.modular.report.Text.escape;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.asymetrik.modular.report.Finding.Kind;
import org.asymetrik.modular.report.Finding.Level;
import org.asymetrik.modular.report.ModuleGraph.Edge;
import org.asymetrik.modular.verification.ModuleAnalysis;
import org.asymetrik.modular.verification.ModuleAnalysis.ClassUse;
import org.asymetrik.modular.verification.ModuleAnalysis.Dependency;
import org.asymetrik.modular.verification.ModuleAnalysis.Exclusions;
import org.asymetrik.modular.verification.ModuleAnalysis.ExternalModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.ModuleDescription;
import org.asymetrik.modular.verification.ModuleAnalysis.TargetKind;
import org.asymetrik.modular.verification.ModuleAnalysis.TypeDescription;

/** Writes the page of the report. Every name that comes from the scanned classes is escaped. */
final class ReportPage {

	/** Findings with more lines than this are folded. */
	private static final int LINES_SHOWN = 8;

	private final ModuleAnalysis analysis;
	private final ModuleGraph graph;
	private final Findings findings;
	private final DiagramLayout layout;
	private final String generated;
	private final List<ModuleDescription> modules;
	/** For a module name, the id of its section. Two modules with the same name share the first one. */
	private final Map<String, String> sectionOf = new HashMap<>();
	private final Map<String, List<Dependency>> dependenciesOf = new HashMap<>();
	/** For the name of a module, the dependencies of the other modules on it. */
	private final Map<String, List<Dependency>> usersOf = new HashMap<>();
	private final Map<List<String>, Edge> edgeOf = new HashMap<>();
	private final Set<String> virtualModules = new TreeSet<>();
	private final Set<String> jpmsModules = new TreeSet<>();
	/** For a finding, the id of its item in the Findings section. */
	private final Map<Finding, String> idOfFinding = new IdentityHashMap<>();
	/** For the name of a module with findings, the most serious of their levels. */
	private final Map<String, Level> levelOf;
	private final StringBuilder out = new StringBuilder();

	ReportPage(ModuleAnalysis analysis, ModuleGraph graph, Findings findings, DiagramLayout layout,
			String generated) {
		this.analysis = analysis;
		this.graph = graph;
		this.findings = findings;
		this.layout = layout;
		this.generated = generated;
		this.modules = analysis.modules();
		for (int i = 0; i < modules.size(); i++) {
			sectionOf.putIfAbsent(modules.get(i).name(), "module-" + (i + 1));
		}
		for (ExternalModuleDescription external : analysis.externalModules()) {
			virtualModules.add(external.name());
		}
		for (Dependency dependency : analysis.dependencies()) {
			dependenciesOf.computeIfAbsent(dependency.moduleName(), name -> new ArrayList<>()).add(dependency);
			if (dependency.target().kind() == TargetKind.MODULE
				&& !dependency.target().name().equals(dependency.moduleName())) {
				usersOf.computeIfAbsent(dependency.target().name(), name -> new ArrayList<>()).add(dependency);
			}
			if (dependency.target().kind() == TargetKind.JPMS_MODULE) {
				jpmsModules.add(dependency.target().name());
			}
		}
		for (Edge edge : graph.edges()) {
			edgeOf.put(List.of(edge.from(), edge.to()), edge);
		}
		List<Finding> all = findings.all();
		for (int i = 0; i < all.size(); i++) {
			idOfFinding.put(all.get(i), "finding-" + (i + 1));
		}
		this.levelOf = findings.levelOfModules();
	}

	String render() {
		out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
			.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
			.append("<title>Module report</title>\n<style>\n").append(resource("report.css"))
			.append("</style>\n</head>\n<body>\n<div class=\"page\">\n");
		masthead();
		summary();
		findings();
		if (!modules.isEmpty()) {
			diagram();
			if (modules.size() > 1) {
				matrix();
			}
			modules();
		}
		outside();
		out.append("<footer>Generated ").append(generated).append(" by modular, from the compiled classes. ")
			.append("The report does not read the sources: sizes are counted in types, methods and class file ")
			.append("bytes, and a use that only exists in source is not seen.</footer>\n");
		out.append("</div>\n<script>\n").append(resource("report.js")).append("</script>\n</body>\n</html>\n");
		return out.toString();
	}

	private void masthead() {
		out.append("<header class=\"masthead\">\n<p class=\"eyebrow\">modular</p>\n<h1>Module report</h1>\n");
		out.append("<p class=\"scanned\">").append(analysis.packages().size() == 1 ? "Package" : "Packages")
			.append(" scanned: ");
		for (int i = 0; i < analysis.packages().size(); i++) {
			out.append(i > 0 ? ", " : "").append(code(analysis.packages().get(i)));
		}
		out.append("</p>\n");
		Exclusions exclusions = analysis.exclusions();
		if (!exclusions.isEmpty()) {
			out.append("<p class=\"scanned\">Left out:");
			if (!exclusions.classes().isEmpty()) {
				out.append(" classes matching ").append(codes(exclusions.classes()))
					.append(exclusions.uses().isEmpty() ? "." : ";");
			}
			if (!exclusions.uses().isEmpty()) {
				out.append(" uses of ").append(codes(exclusions.uses())).append(".");
			}
			out.append("</p>\n");
		}
		out.append("<nav aria-label=\"Sections\"><a href=\"#summary\">Summary</a>")
			.append("<a href=\"#findings\">Findings</a>");
		if (!modules.isEmpty()) {
			out.append("<a href=\"#diagram\">Diagram</a>");
			if (modules.size() > 1) {
				out.append("<a href=\"#matrix\">Dependency matrix</a>");
			}
			out.append("<a href=\"#modules\">Modules</a>");
		}
		if (hasOutside()) {
			out.append("<a href=\"#outside\">Outside the modules</a>");
		}
		out.append("</nav>\n</header>\n");
	}

	private static String codes(List<String> patterns) {
		StringBuilder joined = new StringBuilder();
		for (int i = 0; i < patterns.size(); i++) {
			joined.append(i > 0 ? ", " : "").append(code(patterns.get(i)));
		}
		return joined.toString();
	}

	private void summary() {
		int errors = findings.count(Level.ERROR);
		int warnings = findings.count(Level.WARNING);
		int hints = findings.count(Level.HINT);
		out.append("<section id=\"summary\">\n<h2>Summary</h2>\n");
		if (modules.isEmpty()) {
			out.append("<p class=\"verdict empty\">No module found in the scanned packages.<span>Check the names ")
				.append("of the packages, and that their classes are compiled and on the class path. Test classes ")
				.append("are left out unless the report is built from an analysis that includes them.</span></p>\n");
		} else if (errors > 0) {
			out.append("<p class=\"verdict error\">").append(count(errors, "violation"))
				.append(" of the module rules.<span>The verifier rejects these classes.</span></p>\n");
		} else if (warnings > 0) {
			out.append("<p class=\"verdict warning\">No violation of the module rules, but ")
				.append(count(warnings, "warning")).append(" to look at.</p>\n");
		} else {
			out.append("<p class=\"verdict\">No violation of the module rules, no cycle, no class outside the ")
				.append("modules.</p>\n");
		}

		int types = 0;
		int methods = 0;
		long bytes = 0;
		for (ModuleDescription module : modules) {
			types += module.types().size();
			methods += methods(module);
			bytes += bytes(module);
		}
		int toOutside = 0;
		for (Dependency dependency : analysis.dependencies()) {
			if (dependency.target().kind() != TargetKind.MODULE) {
				toOutside++;
			}
		}
		out.append("<dl class=\"stats\">\n");
		stat("Modules", modules.size(), virtualModules.isEmpty() ? "declared with @Module"
			: "and " + count(virtualModules.size(), "virtual module"));
		stat("Types", types, count(methods, "method") + ", " + Text.bytes(bytes) + " of class files");
		stat("Dependencies", graph.edges().size(), "between modules"
			+ (toOutside > 0 ? ", and " + toOutside + " on what is outside" : ""));
		stat("Findings", errors + warnings + hints, errors + warnings + hints == 0 ? "nothing to report"
			: count(errors, "error") + ", " + count(warnings, "warning") + ", " + count(hints, "hint"));
		out.append("</dl>\n</section>\n");
	}

	private void stat(String name, int value, String sub) {
		out.append("<div><dt>").append(name).append("</dt><dd class=\"value\">").append(value)
			.append("</dd><dd class=\"sub\">").append(escape(sub)).append("</dd></div>\n");
	}

	private void findings() {
		out.append("<section id=\"findings\">\n<h2>Findings</h2>\n");
		List<Finding> all = findings.all();
		if (all.isEmpty()) {
			out.append("<p class=\"none\">Nothing to report.</p>\n</section>\n");
			return;
		}
		out.append("<ul class=\"findings\">\n");
		for (Finding finding : all) {
			String level = levelName(finding.level());
			out.append("<li class=\"finding ").append(level).append("\" id=\"").append(idOfFinding.get(finding))
				.append("\"><span class=\"level\">").append(level)
				.append("</span><div><p class=\"what\">").append(escape(finding.title())).append("</p>");
			if (!finding.details().isEmpty()) {
				boolean folded = finding.details().size() > LINES_SHOWN;
				if (folded) {
					out.append("<details><summary>").append(finding.kind() == Kind.CLASSES_OUTSIDE_MODULES
						? "Show the classes" : "Show the " + finding.details().size() + " lines").append("</summary>");
				}
				out.append("<ul class=\"lines\">");
				for (String line : finding.details()) {
					out.append("<li>").append(escape(line)).append("</li>");
				}
				out.append("</ul>");
				if (folded) {
					out.append("</details>");
				}
			}
			out.append("</div></li>\n");
		}
		out.append("</ul>\n");
		if (findings.count(Level.HINT) > 0) {
			out.append("<p class=\"note\">A hint can be a false alarm. The report reads bytecode: a use that only ")
				.append("exists in source, such as an inlined constant or a source-retention annotation, is not ")
				.append("seen, and an exported package can be an entry point or be used by reflection. A large type, ")
				.append("an exported helper or a shared exception can also be a choice.</p>\n");
		}
		out.append("</section>\n");
	}

	private void diagram() {
		String label = "Diagram of " + count(graph.nodes().size(), "module") + " and "
			+ count(graph.edges().size(), "dependency").replace("dependencys", "dependencies")
			+ (graph.cycles().isEmpty() ? "" : ", with " + count(graph.cycles().size(), "cycle"))
			+ ". The dependency matrix and the module sections below give the same information as text.";
		out.append("<section id=\"diagram\">\n<h2>Diagram</h2>\n<figure>\n<div class=\"scroll\">\n")
			.append(DiagramSvg.render(layout, label, sectionOf, levelOf, this::findingsOf))
			.append("</div>\n<figcaption>")
			.append("An arrow goes from a module to a module it uses, and is wider when there are more uses. ")
			.append("A module sits below the modules that use it. ");
		if (!graph.cycles().isEmpty()) {
			out.append("The arrows in red are part of a cycle: they cannot all point down. ");
		}
		if (!levelOf.isEmpty()) {
			out.append("A box with findings takes the color of the most serious one: red for an error, amber for a ")
				.append("warning, blue for a hint. ");
		}
		out.append("The height of a box follows its number of types.")
			.append("<span class=\"js-only\" hidden> Select a module to highlight its arrows.</span>")
			.append("</figcaption>\n</figure>\n")
			.append("<p id=\"diagram-selection\" class=\"selection\" aria-live=\"polite\" hidden></p>\n</section>\n");
	}

	private void matrix() {
		out.append("<section id=\"matrix\">\n<h2>Dependency matrix</h2>\n<p class=\"lead\">A cell is the number of ")
			.append("uses of the column's module by the row's module.");
		if (!graph.cycles().isEmpty()) {
			out.append(" The cells in red are part of a cycle.");
		}
		out.append("</p>\n<div class=\"scroll\">\n<table class=\"matrix\">\n<thead><tr>")
			.append("<th scope=\"col\" class=\"corner\">uses &rarr;</th>");
		for (int column = 0; column < modules.size(); column++) {
			out.append("<th scope=\"col\" title=\"").append(escape(modules.get(column).name())).append("\">")
				.append(column + 1).append("</th>");
		}
		out.append("</tr></thead>\n<tbody>\n");
		for (int row = 0; row < modules.size(); row++) {
			String from = modules.get(row).name();
			out.append("<tr><th scope=\"row\"><span class=\"index\">").append(row + 1).append("</span>")
				.append(moduleLink(from)).append("</th>");
			for (int column = 0; column < modules.size(); column++) {
				String to = modules.get(column).name();
				Edge edge = edgeOf.get(List.of(from, to));
				if (from.equals(to)) {
					out.append("<td class=\"self\"></td>");
				} else if (edge == null) {
					out.append("<td></td>");
				} else {
					out.append("<td").append(edge.inCycle() ? " class=\"cycle\"" : "").append(" title=\"")
						.append(escape(from + " uses " + to + ": " + count(edge.uses(), "use"))).append("\">")
						.append(edge.uses()).append("</td>");
				}
			}
			out.append("</tr>\n");
		}
		out.append("</tbody>\n</table>\n</div>\n</section>\n");
	}

	private void modules() {
		out.append("<section id=\"modules\">\n<h2>Modules</h2>\n<div class=\"scroll\">\n<table class=\"overview\">\n")
			.append("<thead><tr><th scope=\"col\">Module</th><th scope=\"col\" class=\"num\">Types</th>")
			.append("<th scope=\"col\" class=\"num\">Exported</th>")
			.append("<th scope=\"col\" class=\"num\">Methods</th><th scope=\"col\" class=\"num\">Class files</th>")
			.append("<th scope=\"col\" class=\"num\">Uses</th><th scope=\"col\" class=\"num\">Used by</th>")
			.append("</tr></thead>\n<tbody>\n");
		for (ModuleDescription module : modules) {
			out.append("<tr").append(levelClass(module.name(), " class=\"", "\"")).append("><th scope=\"row\">")
				.append(moduleLink(module.name()));
			if (levelOf.containsKey(module.name())) {
				out.append(" <span class=\"flag ").append(levelName(levelOf.get(module.name()))).append("\">")
					.append(escape(findingsOf(module.name()))).append("</span>");
			}
			out.append("</th><td class=\"num\">")
				.append(module.types().size()).append("</td><td class=\"num\">").append(exportedTypes(module))
				.append("</td><td class=\"num\">")
				.append(methods(module)).append("</td><td class=\"num\">").append(Text.bytes(bytes(module)))
				.append("</td><td class=\"num\">").append(dependenciesOf.getOrDefault(module.name(), List.of()).size())
				.append("</td><td class=\"num\">").append(usersOf.getOrDefault(module.name(), List.of()).size())
				.append("</td></tr>\n");
		}
		out.append("</tbody>\n</table>\n</div>\n<div class=\"modules\">\n");
		for (int i = 0; i < modules.size(); i++) {
			module(i);
		}
		out.append("</div>\n</section>\n");
	}

	private void module(int index) {
		ModuleDescription module = modules.get(index);
		List<Dependency> dependencies = dependenciesOf.getOrDefault(module.name(), List.of());
		List<Dependency> users = usersOf.getOrDefault(module.name(), List.of());
		out.append("<article class=\"module").append(levelClass(module.name(), " ", "")).append("\" id=\"module-")
			.append(index + 1).append("\">\n<header><h3>").append(escape(module.name())).append("</h3>")
			.append(code(module.packageName())).append("</header>\n<dl class=\"facts\">\n");

		// Two modules with the same name share their findings
		List<Finding> about = findings.about(module.name());
		if (!about.isEmpty()) {
			out.append("<dt>Findings</dt><dd><ul class=\"about\">");
			for (Finding finding : about) {
				String level = levelName(finding.level());
				out.append("<li><span class=\"flag ").append(level).append("\">").append(level)
					.append("</span> <a href=\"#").append(idOfFinding.get(finding)).append("\">")
					.append(escape(finding.title())).append("</a></li>");
			}
			out.append("</ul></dd>\n");
		}

		out.append("<dt>Exports</dt><dd>");
		if (module.exports().isEmpty()) {
			out.append("nothing");
		} else {
			out.append("<ul>");
			for (String export : new LinkedHashSet<>(module.exports())) {
				out.append("<li>").append(code(export));
				if (export.equals(".")) {
					out.append(" <span class=\"kind\">its own package</span>");
				}
				if (findings.exportsNotUsed(index).contains(export)) {
					out.append(" <span class=\"flag\">not used by another module</span>");
				}
				out.append("</li>");
			}
			out.append("</ul>");
		}
		out.append("</dd>\n<dt>Requires</dt><dd>");
		if (module.requires().isEmpty()) {
			out.append("nothing");
		} else {
			out.append("<ul>");
			for (String required : new LinkedHashSet<>(module.requires())) {
				String kind = kindOfRequired(required);
				out.append("<li>").append(sectionOf.containsKey(required) ? moduleLink(required) : code(required));
				if (kind == null) {
					out.append(" <span class=\"flag error\">does not exist</span>");
				} else if (!kind.equals("module")) {
					out.append(" <span class=\"kind\">").append(kind).append("</span>");
				}
				if (findings.requiresNotUsed(index).contains(required)) {
					out.append(" <span class=\"flag\">not seen in use</span>");
				}
				out.append("</li>");
			}
			out.append("</ul>");
		}
		int types = module.types().size();
		out.append("</dd>\n<dt>Size</dt><dd>").append(count(types, "type")).append(", ")
			.append(count(methods(module), "method")).append(", ").append(Text.bytes(bytes(module)))
			.append(" of class files</dd>\n<dt>Exported</dt><dd>").append(exportedTypes(module)).append(" of ")
			.append(count(types, "type")).append(", ").append(types - exportedTypes(module))
			.append(" internal</dd>\n</dl>\n");

		out.append("<div class=\"columns\">\n<div><h4>Uses</h4>\n");
		if (dependencies.isEmpty()) {
			out.append("<p class=\"none\">Nothing outside <code>java.*</code>.</p>\n");
		} else {
			out.append("<table class=\"wide\">\n<tbody>\n");
			for (Dependency dependency : dependencies) {
				String name = dependency.target().name();
				boolean declared = module.requires().contains(name);
				out.append("<tr><th scope=\"row\">");
				switch (dependency.target().kind()) {
					case MODULE -> out.append(moduleLink(name));
					case EXTERNAL_MODULE -> out.append(code(name)).append(" <span class=\"kind\">virtual module</span>");
					case JPMS_MODULE -> out.append(code(name)).append(" <span class=\"kind\">JPMS module</span>");
					case UNMANAGED -> out.append(code(name)).append(" <span class=\"flag error\">in no module</span>");
				}
				if (!declared && dependency.target().kind() != TargetKind.UNMANAGED) {
					out.append(" <span class=\"flag error\">not required</span>");
				}
				out.append("</th><td>");
				pairs(dependency);
				out.append("</td></tr>\n");
			}
			out.append("</tbody>\n</table>\n");
		}
		out.append("</div>\n<div><h4>Used by</h4>\n");
		if (users.isEmpty()) {
			out.append("<p class=\"none\">No other module.</p>\n");
		} else {
			out.append("<table class=\"wide\">\n<tbody>\n");
			for (Dependency user : users) {
				out.append("<tr><th scope=\"row\">").append(moduleLink(user.moduleName())).append("</th><td>");
				pairs(user);
				out.append("</td></tr>\n");
			}
			out.append("</tbody>\n</table>\n");
		}
		out.append("</div>\n</div>\n");

		if (types > 0) {
			out.append("<details class=\"types\"><summary>").append(count(types, "type")).append("</summary>\n")
				.append("<div class=\"scroll\">\n<table>\n<thead><tr><th scope=\"col\">Type</th><th scope=\"col\">")
				.append("Package</th><th scope=\"col\" class=\"num\">Methods</th><th scope=\"col\" class=\"num\">")
				.append("Class files</th></tr></thead>\n<tbody>\n");
			for (TypeDescription type : module.types()) {
				out.append("<tr><th scope=\"row\">").append(code(relativeName(type.name(), module))).append("</th><td>")
					.append(type.exported() ? "exported" : "<span class=\"kind\">internal</span>")
					.append("</td><td class=\"num\">").append(type.methods()).append("</td><td class=\"num\">")
					.append(type.classFileBytes().isPresent() ? Text.bytes(type.classFileBytes().getAsLong())
						: "unknown").append("</td></tr>\n");
			}
			out.append("</tbody>\n</table>\n</div>\n</details>\n");
		}
		out.append("</article>\n");
	}

	/** The pairs of classes behind a dependency, folded under their number. */
	private void pairs(Dependency dependency) {
		out.append("<details><summary>").append(count(dependency.uses().size(), "use"))
			.append("</summary><ul class=\"pairs\">");
		for (ClassUse use : dependency.uses()) {
			out.append("<li>").append(code(use.consumerClass())).append(" &rarr; ").append(code(use.usedClass()))
				.append("</li>");
		}
		out.append("</ul></details>");
	}

	/** "1 warning, 2 hints": the findings about a module, by level, or an empty text when it has none. */
	private String findingsOf(String moduleName) {
		List<Finding> about = findings.about(moduleName);
		List<String> parts = new ArrayList<>();
		for (Level level : Level.values()) {
			long number = about.stream().filter(finding -> finding.level() == level).count();
			if (number > 0) {
				parts.add(count(number, levelName(level)));
			}
		}
		return String.join(", ", parts);
	}

	/** The level of a module's findings as a class, between a prefix and a suffix, or nothing when it has none. */
	private String levelClass(String moduleName, String prefix, String suffix) {
		Level level = levelOf.get(moduleName);
		return level == null ? "" : prefix + levelName(level) + suffix;
	}

	private static String levelName(Level level) {
		return level.name().toLowerCase(java.util.Locale.ROOT);
	}

	private boolean hasOutside() {
		return !virtualModules.isEmpty() || analysis.dependencies().stream()
			.anyMatch(dependency -> dependency.target().kind() != TargetKind.MODULE);
	}

	/** The virtual modules, the JPMS modules and the unmanaged packages, with the modules that use them. */
	private void outside() {
		if (!hasOutside()) {
			return;
		}
		Map<TargetKind, Map<String, Set<String>>> users = new TreeMap<>();
		for (Dependency dependency : analysis.dependencies()) {
			if (dependency.target().kind() != TargetKind.MODULE) {
				users.computeIfAbsent(dependency.target().kind(), kind -> new TreeMap<>())
					.computeIfAbsent(dependency.target().name(), name -> new TreeSet<>())
					.add(dependency.moduleName());
			}
		}
		out.append("<section id=\"outside\">\n<h2>Outside the modules</h2>\n<p class=\"lead\">What the modules use ")
			.append("that is not a module: the virtual modules declared with <code>@ExternalModule</code>, the ")
			.append("JPMS modules of the libraries and of the JDK, and the packages that are in neither.</p>\n")
			.append("<div class=\"scroll\">\n<table class=\"wide\">\n<thead><tr><th scope=\"col\">Name</th>")
			.append("<th scope=\"col\">Kind</th><th scope=\"col\">Packages</th><th scope=\"col\">Used by</th>")
			.append("</tr></thead>\n<tbody>\n");
		Map<String, Set<String>> virtualUsers = users.getOrDefault(TargetKind.EXTERNAL_MODULE, Map.of());
		for (ExternalModuleDescription external : analysis.externalModules()) {
			out.append("<tr><th scope=\"row\">").append(code(external.name())).append("</th><td>virtual module</td><td>");
			for (int i = 0; i < external.packages().size(); i++) {
				out.append(i > 0 ? ", " : "").append(code(external.packages().get(i)));
			}
			out.append("</td><td>").append(moduleLinks(virtualUsers.getOrDefault(external.name(), Set.of())))
				.append("</td></tr>\n");
		}
		users.getOrDefault(TargetKind.JPMS_MODULE, Map.of()).forEach((name, usedBy) -> out
			.append("<tr><th scope=\"row\">").append(code(name)).append("</th><td>JPMS module</td><td></td><td>")
			.append(moduleLinks(usedBy)).append("</td></tr>\n"));
		users.getOrDefault(TargetKind.UNMANAGED, Map.of()).forEach((name, usedBy) -> out
			.append("<tr><th scope=\"row\">").append(code(name))
			.append("</th><td><span class=\"flag error\">in no module</span></td><td>").append(code(name))
			.append("</td><td>").append(moduleLinks(usedBy)).append("</td></tr>\n"));
		out.append("</tbody>\n</table>\n</div>\n</section>\n");
	}

	/** What a name in {@code requires} is, or {@code null} when nothing has this name. */
	private String kindOfRequired(String name) {
		if (sectionOf.containsKey(name)) {
			return "module";
		}
		if (virtualModules.contains(name)) {
			return "virtual module";
		}
		if (jpmsModules.contains(name) || ModuleFinder.ofSystem().find(name).isPresent()) {
			return "JPMS module";
		}
		return null;
	}

	private String moduleLinks(Set<String> names) {
		if (names.isEmpty()) {
			return "<span class=\"kind\">no module</span>";
		}
		StringBuilder links = new StringBuilder();
		for (String name : names) {
			links.append(links.length() > 0 ? ", " : "").append(moduleLink(name));
		}
		return links.toString();
	}

	private String moduleLink(String name) {
		String section = sectionOf.get(name);
		return section == null ? code(name) : "<a href=\"#" + section + "\">" + code(name) + "</a>";
	}

	private static String code(String text) {
		return "<code>" + escape(text) + "</code>";
	}

	/** The name of a type without the package of its module. */
	private static String relativeName(String typeName, ModuleDescription module) {
		String prefix = module.packageName() + ".";
		return typeName.startsWith(prefix) ? typeName.substring(prefix.length()) : typeName;
	}

	private static int methods(ModuleDescription module) {
		return module.types().stream().mapToInt(TypeDescription::methods).sum();
	}

	private static long bytes(ModuleDescription module) {
		return module.types().stream().mapToLong(type -> type.classFileBytes().orElse(0)).sum();
	}

	private static int exportedTypes(ModuleDescription module) {
		return (int) module.types().stream().filter(TypeDescription::exported).count();
	}

	private static String resource(String name) {
		try (InputStream stream = ReportPage.class.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("Missing resource of the report: " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
