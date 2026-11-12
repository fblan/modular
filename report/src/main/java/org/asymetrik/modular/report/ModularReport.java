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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

import org.asymetrik.modular.verification.ModuleAnalysis;
import org.asymetrik.modular.verification.ModuleAnalysis.ClassScope;
import org.asymetrik.modular.verification.ModuleAnalysis.Exclusions;
import org.asymetrik.modular.verification.ModuleAnalysis.ModuleDescription;

/**
 * An HTML report on the modules of an application, to check that they are sound: how big each module is, what
 * really depends on what, where the cycles are, which {@code requires} and {@code exports} are no longer needed.
 *
 * <pre>{@code
 * ModularReport.of("com.example").writeTo(Path.of("target/modular-report"));
 * }</pre>
 *
 * <p>The report is one file, {@code index.html}, that needs nothing else: no network access, no other file.
 * It is built from the compiled classes found on the class path, like {@link ModuleAnalysis}, so it has to run
 * with the class path of the application: from a test, or with {@link #main(String[])}.
 *
 * <p>Apart from its generation time, the report of the same classes is always the same.
 */
public final class ModularReport {

	private static final String EXCLUDE_CLASS = "--exclude-class=";
	private static final String EXCLUDE_USE = "--exclude-use=";

	private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'",
		Locale.ROOT);

	private final ModuleAnalysis analysis;
	private final Clock clock;
	private final ModuleGraph graph;
	private final Findings findings;

	private ModularReport(ModuleAnalysis analysis, Clock clock) {
		this.analysis = analysis;
		this.clock = clock;
		this.graph = ModuleGraph.of(analysis);
		this.findings = new Findings(analysis, graph);
	}

	/**
	 * Reports on the modules of some packages, without their test classes.
	 *
	 * @param packages the packages to scan, with their sub-packages
	 * @return the report
	 */
	public static ModularReport of(String... packages) {
		return of(List.of(packages));
	}

	/**
	 * Reports on the modules of some packages, without their test classes.
	 *
	 * @param packages the packages to scan, with their sub-packages
	 * @return the report
	 */
	public static ModularReport of(List<String> packages) {
		return of(ModuleAnalysis.of(ClassScope.WITHOUT_TESTS, packages));
	}

	/**
	 * Reports on an analysis: the way to choose which classes are scanned.
	 *
	 * @param analysis the modules to report on
	 * @return the report
	 */
	public static ModularReport of(ModuleAnalysis analysis) {
		return new ModularReport(analysis, Clock.systemUTC());
	}

	/** The same report, with another clock for its generation time. */
	ModularReport withClock(Clock clock) {
		return new ModularReport(analysis, clock);
	}

	/**
	 * What the report asks its reader to look at: the violations of the module rules, the cycles, the classes
	 * outside any module, and the {@code requires} and {@code exports} that seem unused.
	 *
	 * @return the findings: errors first, then warnings, then hints
	 */
	public List<Finding> findings() {
		return findings.all();
	}

	/**
	 * The report as one HTML page.
	 *
	 * @return the page
	 */
	public String html() {
		SortedMap<String, Integer> typesByModule = new TreeMap<>();
		for (ModuleDescription module : analysis.modules()) {
			typesByModule.merge(module.name(), module.types().size(), Integer::sum);
		}
		DiagramLayout layout = DiagramLayout.of(typesByModule, graph.edges());
		String generated = GENERATED.format(clock.instant().atOffset(ZoneOffset.UTC));
		return new ReportPage(analysis, graph, findings, layout, generated).render();
	}

	/**
	 * Writes the report as {@code index.html} in a directory, which is created when it does not exist.
	 *
	 * @param directory where to write
	 * @return the file written
	 * @throws UncheckedIOException when the file cannot be written
	 */
	public Path writeTo(Path directory) {
		try {
			Files.createDirectories(directory);
			Path file = directory.resolve("index.html");
			Files.writeString(file, html(), StandardCharsets.UTF_8);
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write the module report in " + directory, e);
		}
	}

	/**
	 * Writes the report of some packages. The classes of the application have to be on the class path.
	 *
	 * <p>Arguments: {@code [--exclude-class=<pattern>]... [--exclude-use=<pattern>]... <output directory>
	 * <package>...}. The options fill the {@link Exclusions} of the analysis, and can be repeated.
	 *
	 * @param args the options, then the output directory, then the packages to scan
	 */
	public static void main(String[] args) {
		List<String> excludedClasses = new ArrayList<>();
		List<String> excludedUses = new ArrayList<>();
		List<String> operands = new ArrayList<>();
		for (String arg : args) {
			if (arg.startsWith(EXCLUDE_CLASS)) {
				excludedClasses.add(arg.substring(EXCLUDE_CLASS.length()));
			} else if (arg.startsWith(EXCLUDE_USE)) {
				excludedUses.add(arg.substring(EXCLUDE_USE.length()));
			} else if (arg.startsWith("--")) {
				usage("Unknown option: " + arg);
				return;
			} else {
				operands.add(arg);
			}
		}
		if (operands.size() < 2) {
			usage("An output directory and at least one package are needed.");
			return;
		}
		Exclusions exclusions;
		try {
			exclusions = new Exclusions(excludedClasses, excludedUses);
		} catch (IllegalArgumentException blank) {
			usage(blank.getMessage());
			return;
		}
		ModularReport report = of(ModuleAnalysis.of(ClassScope.WITHOUT_TESTS, operands.subList(1, operands.size()),
			exclusions));
		Path file = report.writeTo(Path.of(operands.get(0)));
		System.out.println("Module report: " + file.toAbsolutePath());
		System.out.println(Text.count(report.findings().size(), "finding"));
	}

	private static void usage(String problem) {
		System.err.println(problem);
		System.err.println("Usage: ModularReport [" + EXCLUDE_CLASS + "<pattern>]... [" + EXCLUDE_USE
			+ "<pattern>]... <output directory> <package>...");
		System.exit(2);
	}
}
