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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.asymetrik.modular.annotations.ExternalModule;
import org.asymetrik.modular.annotations.ExternalModules;
import org.asymetrik.modular.annotations.Module;
import org.asymetrik.modular.verification.ModularVerifier.DuplicateModuleNameViolation;
import org.asymetrik.modular.verification.ModularVerifier.ExportViolation;
import org.asymetrik.modular.verification.ModularVerifier.Invalid;
import org.asymetrik.modular.verification.ModularVerifier.NestedModuleViolation;
import org.asymetrik.modular.verification.ModularVerifier.RequiresViolation;
import org.asymetrik.modular.verification.ModularVerifier.UnknownRequiredModuleViolation;
import org.asymetrik.modular.verification.ModularVerifier.UnmanagedDependencyViolation;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * What the compiled classes of some packages say about their modules: the modules and their types, what each
 * module really uses, and every violation of the module rules.
 *
 * <p>It is built once, from the class path, by {@link #of(String...)}. Everything it returns is sorted, so two
 * analyses of the same classes are equal.
 */
public final class ModuleAnalysis {

	/** Which classes of the scanned packages are analysed. */
	public enum ClassScope {
		/** Every class on the class path. */
		ALL_CLASSES,
		/** Leaves out the classes of test output directories (such as {@code target/test-classes}). */
		WITHOUT_TESTS
	}

	/**
	 * Classes left out of an analysis, such as the ones a framework generates in the application's packages.
	 *
	 * <p>A pattern matches a fully qualified class name, nested classes written with {@code $}: {@code *} matches
	 * any characters except {@code .}, {@code **} any characters, and everything else is literal. For example,
	 * {@code **$$*} matches {@code com.example.web.Shop$$Adapter} and {@code **._*} matches
	 * {@code com.example.web._Components}.
	 *
	 * @param classes scanned classes to leave out entirely, as if they were not there: they are not measured, their
	 *                uses are not analysed, and they are not outside any module. A nested class is left out when it
	 *                or its top-level class matches
	 * @param uses used classes to ignore: a use of one of them is no dependency and no violation
	 */
	public record Exclusions(List<String> classes, List<String> uses) {

		/** No exclusion: every scanned class and every use is analysed. */
		public static final Exclusions NONE = new Exclusions(List.of(), List.of());

		/**
		 * Checks and copies the patterns.
		 *
		 * @throws IllegalArgumentException when a pattern is blank
		 */
		public Exclusions {
			classes = checked(classes);
			uses = checked(uses);
		}

		/**
		 * Whether there is no exclusion.
		 *
		 * @return true when both lists are empty
		 */
		public boolean isEmpty() {
			return classes.isEmpty() && uses.isEmpty();
		}

		boolean excludesClass(String className, String topLevelName) {
			return matchesAny(classes, className) || matchesAny(classes, topLevelName);
		}

		boolean excludesUse(String className) {
			return matchesAny(uses, className);
		}

		private static List<String> checked(List<String> patterns) {
			for (String pattern : patterns) {
				if (pattern == null || pattern.isBlank()) {
					throw new IllegalArgumentException("A class pattern cannot be blank: " + patterns);
				}
			}
			return List.copyOf(patterns);
		}

		private static boolean matchesAny(List<String> patterns, String className) {
			return patterns.stream().anyMatch(pattern -> toRegex(pattern).matcher(className).matches());
		}

		static Pattern toRegex(String pattern) {
			StringBuilder regex = new StringBuilder();
			int i = 0;
			while (i < pattern.length()) {
				if (pattern.startsWith("**", i)) {
					regex.append(".*");
					i += 2;
				} else if (pattern.charAt(i) == '*') {
					regex.append("[^.]*");
					i++;
				} else {
					regex.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
					i++;
				}
			}
			return Pattern.compile(regex.toString());
		}
	}

	/** What a module depends on. */
	public enum TargetKind {
		/** A module declared with {@link Module}. */
		MODULE,
		/** A virtual module declared with {@link ExternalModule}. */
		EXTERNAL_MODULE,
		/** A JPMS module: a JDK module, or the module of a class path jar. */
		JPMS_MODULE,
		/** A package that no module, virtual module or JPMS module owns. */
		UNMANAGED
	}

	/**
	 * A top-level type of a module. Its nested, local and anonymous classes are counted with it.
	 *
	 * @param name its fully qualified name
	 * @param packageName its package
	 * @param exported whether the module exports that package
	 * @param methods its methods, without the constructors and the ones the compiler adds
	 * @param classFileBytes the size of its class files, empty when they cannot be read
	 * @param exception whether it is a {@link Throwable}
	 * @param staticHelper whether it is a class of static helpers: not an interface, enum, record or annotation,
	 *                     with at least one method, every method and field static, every constructor private, and
	 *                     no method returning a type of its module (or an array of one), which would make it a
	 *                     factory. Its nested classes are not looked at.
	 */
	public record TypeDescription(String name, String packageName, boolean exported, int methods,
								  OptionalLong classFileBytes, boolean exception, boolean staticHelper) {

		/**
		 * A type that is neither an exception nor a class of static helpers.
		 *
		 * @param name its fully qualified name
		 * @param packageName its package
		 * @param exported whether the module exports that package
		 * @param methods its methods, without the constructors and the ones the compiler adds
		 * @param classFileBytes the size of its class files, empty when they cannot be read
		 */
		public TypeDescription(String name, String packageName, boolean exported, int methods,
							   OptionalLong classFileBytes) {
			this(name, packageName, exported, methods, classFileBytes, false, false);
		}
	}

	/**
	 * A module declared with {@link Module}.
	 *
	 * @param name its name
	 * @param packageName the package carrying the annotation
	 * @param exports its {@code exports}, as written
	 * @param requires its {@code requires}, as written
	 * @param types its top-level types, sorted by name
	 */
	public record ModuleDescription(String name, String packageName, List<String> exports, List<String> requires,
									List<TypeDescription> types) {}

	/**
	 * A virtual module declared with {@link ExternalModule}.
	 *
	 * @param name its name
	 * @param packages the packages it covers, with their sub-packages
	 */
	public record ExternalModuleDescription(String name, List<String> packages) {}

	/**
	 * The other end of a {@link Dependency}.
	 *
	 * @param kind what it is
	 * @param name the module's name, or the package for {@link TargetKind#UNMANAGED}
	 */
	public record Target(TargetKind kind, String name) {}

	/**
	 * One class using another one. Nested classes are named by their top-level type when it is known.
	 *
	 * @param consumerClass the class of the module
	 * @param usedClass the class it uses
	 * @param createsInstances whether the consumer, or one of its nested classes, calls a constructor of the used
	 *                         class
	 * @param annotationsOnly whether the consumer and its nested classes use the used class only in annotations: as
	 *                        an annotation's type, or in one of its values, such as an enum constant. A user of the
	 *                        consumer then never has to know the used class
	 */
	public record ClassUse(String consumerClass, String usedClass, boolean createsInstances, boolean annotationsOnly) {

		/**
		 * A use that is not only in annotations.
		 *
		 * @param consumerClass the class of the module
		 * @param usedClass the class it uses
		 * @param createsInstances whether the consumer, or one of its nested classes, calls a constructor of the
		 *                         used class
		 */
		public ClassUse(String consumerClass, String usedClass, boolean createsInstances) {
			this(consumerClass, usedClass, createsInstances, false);
		}

		/**
		 * A use that creates no instance of the used class, and is not only in annotations.
		 *
		 * @param consumerClass the class of the module
		 * @param usedClass the class it uses
		 */
		public ClassUse(String consumerClass, String usedClass) {
			this(consumerClass, usedClass, false, false);
		}
	}

	/**
	 * What a module really uses of one target, whether it is allowed to or not. Uses of {@code java.base}, of the
	 * module itself and of {@link Exclusions#uses() excluded} classes are left out. A JDK module other than
	 * {@code java.base}, such as {@code java.net.http}, is a {@link TargetKind#JPMS_MODULE} target: its
	 * {@code java.*} packages can be used without being required, so its uses break no rule.
	 *
	 * @param moduleName the module that uses the target
	 * @param target what it uses
	 * @param uses the classes behind the dependency, sorted
	 */
	public record Dependency(String moduleName, Target target, List<ClassUse> uses) {}

	/** A virtual module: named packages outside any module, each covering its sub-packages. */
	private record ExternalModuleInfo(String name, String[] packages) {
		/** Length of the longest listed package covering {@code packageName}, or -1 if none does. */
		int coverageOf(String packageName) {
			return Arrays.stream(packages)
				.filter(p -> packageName.equals(p) || packageName.startsWith(p + "."))
				.mapToInt(String::length)
				.max()
				.orElse(-1);
		}
	}

	private record ModuleInfo(String name, String packageName, String[] exports, String[] requires) {
		boolean doesRequire(String moduleName) {
			return Arrays.asList(requires).contains(moduleName);
		}

		boolean contains(String somePackage) {
			return somePackage.equals(packageName) || somePackage.startsWith(packageName + ".");
		}

		boolean isDescendantOf(ModuleInfo other) {
			return this.packageName.startsWith(other.packageName + ".");
		}

		int nestingLevelFrom(ModuleInfo parent) {
			String relativePath = this.packageName.substring(parent.packageName.length() + 1);
			return relativePath.split("\\.").length;
		}
	}

	/** A top-level type being measured: its nested classes add to it. */
	private static final class TypeSize {
		private final String packageName;
		private int methods;
		private long bytes;
		private boolean bytesKnown;
		private boolean exception;
		private boolean staticHelper;

		TypeSize(String packageName) {
			this.packageName = packageName;
		}
	}

	private static final Comparator<ClassUse> BY_CONSUMER_THEN_USED =
		Comparator.comparing(ClassUse::consumerClass).thenComparing(ClassUse::usedClass);

	private static final Comparator<Target> BY_KIND_THEN_NAME =
		Comparator.comparing(Target::kind).thenComparing(Target::name);

	private final List<String> packages;
	private final Exclusions exclusions;
	private final List<ModuleDescription> modules;
	private final List<ExternalModuleDescription> externalModules;
	private final List<Dependency> dependencies;
	private final List<Invalid> violations;
	private final List<String> unassignedClasses;

	/** JPMS module name by package, as found by {@link #findJpmsModuleName(JavaClass)}. */
	private final Map<String, Optional<String>> jpmsModuleByPackage = new HashMap<>();

	/**
	 * Analyses every class of the given packages and their sub-packages.
	 *
	 * @param packages the packages to scan
	 * @return the analysis
	 */
	public static ModuleAnalysis of(String... packages) {
		return of(ClassScope.ALL_CLASSES, Arrays.asList(packages));
	}

	/**
	 * Analyses every class of the given packages and their sub-packages.
	 *
	 * @param packages the packages to scan
	 * @return the analysis
	 */
	public static ModuleAnalysis of(List<String> packages) {
		return of(ClassScope.ALL_CLASSES, packages);
	}

	/**
	 * Analyses the classes of the given packages and their sub-packages.
	 *
	 * @param scope whether test classes are left out
	 * @param packages the packages to scan
	 * @return the analysis
	 */
	public static ModuleAnalysis of(ClassScope scope, List<String> packages) {
		return of(scope, packages, Exclusions.NONE);
	}

	/**
	 * Analyses the classes of the given packages and their sub-packages, leaving some classes out.
	 *
	 * @param scope whether test classes are left out
	 * @param packages the packages to scan
	 * @param exclusions the classes to leave out, and the uses to ignore
	 * @return the analysis
	 */
	public static ModuleAnalysis of(ClassScope scope, List<String> packages, Exclusions exclusions) {
		return new ModuleAnalysis(scope, packages, exclusions);
	}

	private ModuleAnalysis(ClassScope scope, List<String> packages, Exclusions exclusions) {
		this.packages = List.copyOf(packages);
		this.exclusions = exclusions;

		ClassFileImporter importer = new ClassFileImporter();
		if (scope == ClassScope.WITHOUT_TESTS) {
			importer = importer.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
		}
		JavaClasses imported = importer.importPackages(this.packages.toArray(new String[0]));
		List<JavaClass> classes = imported.stream()
			.filter(clazz -> !exclusions.excludesClass(clazz.getName(), topLevelName(clazz)))
			.sorted(Comparator.comparing(JavaClass::getName))
			.toList();

		List<ModuleInfo> moduleInfos = findAllModules(classes);
		List<ExternalModuleInfo> externalInfos = findAllExternalModules(classes);

		// Records are equal by value: the same violation found through two accesses is kept once
		Set<Invalid> found = new LinkedHashSet<>();
		checkNesting(moduleInfos, found);
		checkModuleNames(moduleInfos, externalInfos, collectUsedJpmsModules(classes, moduleInfos), found);

		Map<ModuleInfo, Map<String, TypeSize>> typesByModule = new LinkedHashMap<>();
		Map<ModuleInfo, Map<Target, TreeSet<ClassUse>>> usesByModule = new LinkedHashMap<>();
		for (ModuleInfo module : moduleInfos) {
			typesByModule.put(module, new TreeMap<>());
			usesByModule.put(module, new TreeMap<>(BY_KIND_THEN_NAME));
		}
		Set<String> unassigned = new TreeSet<>();

		for (JavaClass clazz : classes) {
			// package-info only carries the module declaration, it is not module code
			if (clazz.getSimpleName().equals("package-info")) {
				continue;
			}
			Optional<ModuleInfo> owner = findModuleForPackage(clazz.getPackageName(), moduleInfos);
			if (owner.isEmpty()) {
				unassigned.add(topLevelName(clazz));
				continue;
			}
			measure(clazz, owner.get(), typesByModule.get(owner.get()));
			analyseDependencies(clazz, owner.get(), moduleInfos, externalInfos, usesByModule.get(owner.get()), found);
		}

		this.modules = moduleInfos.stream()
			.map(module -> describe(module, typesByModule.get(module)))
			.toList();
		this.externalModules = externalInfos.stream()
			.map(external -> new ExternalModuleDescription(external.name(), List.of(external.packages())))
			.toList();
		List<Dependency> allDependencies = new ArrayList<>();
		usesByModule.forEach((module, usesByTarget) -> usesByTarget.forEach((target, uses) ->
			allDependencies.add(new Dependency(module.name(), target, List.copyOf(uses)))));
		this.dependencies = List.copyOf(allDependencies);
		this.violations = List.copyOf(found);
		this.unassignedClasses = List.copyOf(unassigned);
	}

	/**
	 * The scanned packages.
	 *
	 * @return the packages given to {@link #of(String...)}
	 */
	public List<String> packages() {
		return packages;
	}

	/**
	 * The classes left out of the analysis, and the uses it ignores.
	 *
	 * @return the exclusions given to {@link #of(ClassScope, List, Exclusions)}, else {@link Exclusions#NONE}
	 */
	public Exclusions exclusions() {
		return exclusions;
	}

	/**
	 * The modules declared with {@link Module} in the scanned packages.
	 *
	 * @return the modules, sorted by name, then by package
	 */
	public List<ModuleDescription> modules() {
		return modules;
	}

	/**
	 * The virtual modules declared with {@link ExternalModule} in the scanned packages.
	 *
	 * @return the virtual modules, sorted by name
	 */
	public List<ExternalModuleDescription> externalModules() {
		return externalModules;
	}

	/**
	 * What each module really uses, one entry per module and target.
	 *
	 * @return the dependencies, in the order of {@link #modules()}, then by kind and name of the target
	 */
	public List<Dependency> dependencies() {
		return dependencies;
	}

	/**
	 * Every violation of the module rules: nested modules first, then module names, then dependencies.
	 *
	 * @return the violations, empty when the modules are valid
	 */
	public List<Invalid> violations() {
		return violations;
	}

	/**
	 * The scanned top-level types that are in no module.
	 *
	 * @return their fully qualified names, sorted
	 */
	public List<String> unassignedClasses() {
		return unassignedClasses;
	}

	private static void checkNesting(List<ModuleInfo> modules, Set<Invalid> found) {
		for (ModuleInfo parent : modules) {
			for (ModuleInfo candidate : modules) {
				if (candidate.isDescendantOf(parent)) {
					found.add(new NestedModuleViolation(
						parent.name(),
						candidate.name(),
						parent.packageName(),
						candidate.packageName(),
						candidate.nestingLevelFrom(parent)
					));
				}
			}
		}
	}

	private static void checkModuleNames(List<ModuleInfo> modules, List<ExternalModuleInfo> externalModules,
										 Set<String> usedJpmsModules, Set<Invalid> found) {
		// Modules and virtual modules share one namespace, as both are referenced by name in requires
		Map<String, String> declarationByName = new HashMap<>();
		for (ModuleInfo module : modules) {
			String first = declarationByName.putIfAbsent(module.name(), module.packageName());
			if (first != null) {
				found.add(new DuplicateModuleNameViolation(module.name(), first, module.packageName()));
			}
		}
		for (ExternalModuleInfo external : externalModules) {
			String declaration = "external module " + Arrays.toString(external.packages());
			String first = declarationByName.putIfAbsent(external.name(), declaration);
			if (first != null) {
				found.add(new DuplicateModuleNameViolation(external.name(), first, declaration));
			}
		}

		for (ModuleInfo module : modules) {
			for (String required : module.requires()) {
				if (!declarationByName.containsKey(required) && !usedJpmsModules.contains(required)
					&& ModuleFinder.ofSystem().find(required).isEmpty()) {
					found.add(new UnknownRequiredModuleViolation(module.name(), module.packageName(), required));
				}
			}
		}
	}

	/** Records what a class of {@code consumer} uses outside its module, and the rules these uses break. */
	private void analyseDependencies(JavaClass clazz, ModuleInfo consumer, List<ModuleInfo> modules,
									 List<ExternalModuleInfo> externalModules, Map<Target, TreeSet<ClassUse>> usesByTarget,
									 Set<Invalid> found) {
		// For each used class, whether every dependency on it comes from an annotation
		Map<JavaClass, Boolean> annotationsOnly = new HashMap<>();
		for (com.tngtech.archunit.core.domain.Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
			JavaClass target = dependency.getTargetClass().getBaseComponentType();
			if (!target.isPrimitive() && !exclusions.excludesUse(target.getName())) {
				annotationsOnly.merge(target, fromAnnotation(dependency), Boolean::logicalAnd);
			}
		}
		List<JavaClass> used = annotationsOnly.keySet().stream()
			.sorted(Comparator.comparing(JavaClass::getName))
			.toList();
		// A constructor's call to super(...) creates no instance of the superclass
		Optional<String> superclass = clazz.getRawSuperclass().map(JavaClass::getName);
		Set<String> created = clazz.getConstructorCallsFromSelf().stream()
			.filter(call -> !(call.getOrigin() instanceof JavaConstructor
				&& superclass.equals(Optional.of(call.getTargetOwner().getName()))))
			.map(call -> call.getTargetOwner().getName())
			.collect(Collectors.toSet());

		for (JavaClass dependency : used) {
			String depPackage = dependency.getPackageName();
			ClassUse use = new ClassUse(topLevelName(clazz), topLevelName(dependency),
				created.contains(dependency.getName()), annotationsOnly.get(dependency));
			// java.* is the only package that can be used without being in a module: no rule to check
			if (isJavaPackage(depPackage)) {
				findJpmsModuleName(dependency)
					.filter(jdkModule -> !jdkModule.equals("java.base"))
					.ifPresent(jdkModule -> addUse(usesByTarget.computeIfAbsent(
						new Target(TargetKind.JPMS_MODULE, jdkModule), t -> new TreeSet<>(BY_CONSUMER_THEN_USED)), use));
				continue;
			}
			Optional<ModuleInfo> provider = findModuleForPackage(depPackage, modules);

			if (provider.isEmpty()) {
				Target target = analyseExternalDependency(consumer, clazz, dependency, externalModules, found);
				addUse(usesByTarget.computeIfAbsent(target, t -> new TreeSet<>(BY_CONSUMER_THEN_USED)), use);
				continue;
			}
			if (provider.get().equals(consumer)) {
				continue;
			}

			addUse(usesByTarget.computeIfAbsent(new Target(TargetKind.MODULE, provider.get().name()),
				t -> new TreeSet<>(BY_CONSUMER_THEN_USED)), use);

			if (!consumer.doesRequire(provider.get().name())) {
				found.add(new RequiresViolation(
					consumer.name(),
					consumer.packageName(),
					clazz.getFullName(),
					provider.get().name(),
					provider.get().packageName(),
					dependency.getFullName()
				));
			} else if (!isPackageExported(depPackage, provider.get())) {
				found.add(new ExportViolation(
					consumer.name(),
					consumer.packageName(),
					clazz.getFullName(),
					provider.get().name(),
					provider.get().packageName(),
					dependency.getFullName(),
					depPackage
				));
			}
		}
	}

	/**
	 * Whether a dependency comes from an annotation: its type, or one of its values. ArchUnit tells it only in the
	 * description, which it writes as "... is annotated with &lt;type&gt;" or "... has annotation member of type
	 * &lt;type&gt;". {@code ModuleAnalysisTest} checks it on each kind of annotation.
	 */
	private static boolean fromAnnotation(com.tngtech.archunit.core.domain.Dependency dependency) {
		String target = "<" + dependency.getTargetClass().getName() + ">";
		String description = dependency.getDescription();
		return description.contains(" is annotated with " + target)
			|| description.contains(" has annotation member of type " + target);
	}

	/**
	 * Adds a use. A type and its nested classes make one consumer: it creates instances if one of them does, and
	 * uses the class only in annotations if all of them do.
	 */
	private static void addUse(TreeSet<ClassUse> uses, ClassUse use) {
		// The set compares consumer and used class only
		ClassUse known = uses.floor(use);
		if (known == null || !known.consumerClass().equals(use.consumerClass())
			|| !known.usedClass().equals(use.usedClass())) {
			uses.add(use);
			return;
		}
		uses.remove(known);
		uses.add(new ClassUse(use.consumerClass(), use.usedClass(), known.createsInstances() || use.createsInstances(),
			known.annotationsOnly() && use.annotationsOnly()));
	}

	/** The owner of a class that is in no module, and the rule its use breaks, if any. */
	private Target analyseExternalDependency(ModuleInfo consumer, JavaClass clazz, JavaClass dependency,
											 List<ExternalModuleInfo> externalModules, Set<Invalid> found) {
		String depPackage = dependency.getPackageName();

		// The most specific virtual module covering the package, if any
		Optional<ExternalModuleInfo> external = externalModules.stream()
			.filter(e -> e.coverageOf(depPackage) >= 0)
			.reduce((e1, e2) -> e1.coverageOf(depPackage) >= e2.coverageOf(depPackage) ? e1 : e2);

		if (external.isPresent()) {
			// Everything in a virtual module is exported, it only has to be required
			if (!consumer.doesRequire(external.get().name())) {
				found.add(new RequiresViolation(
					consumer.name(),
					consumer.packageName(),
					clazz.getFullName(),
					external.get().name(),
					String.join(", ", external.get().packages()),
					dependency.getFullName()
				));
			}
			return new Target(TargetKind.EXTERNAL_MODULE, external.get().name());
		}

		Optional<String> jpmsModule = findJpmsModuleName(dependency);
		if (jpmsModule.isEmpty()) {
			found.add(new UnmanagedDependencyViolation(
				consumer.name(),
				consumer.packageName(),
				clazz.getFullName(),
				dependency.getFullName(),
				depPackage
			));
			return new Target(TargetKind.UNMANAGED, depPackage);
		}
		if (!consumer.doesRequire(jpmsModule.get())) {
			found.add(new RequiresViolation(
				consumer.name(),
				consumer.packageName(),
				clazz.getFullName(),
				jpmsModule.get(),
				depPackage,
				dependency.getFullName()
			));
		}
		return new Target(TargetKind.JPMS_MODULE, jpmsModule.get());
	}

	/**
	 * JPMS modules of the classes outside {@code java.*} and outside the modules that the scanned classes use,
	 * so that requiring one of them is not reported as unknown.
	 */
	private Set<String> collectUsedJpmsModules(List<JavaClass> classes, List<ModuleInfo> modules) {
		Set<String> used = new HashSet<>();
		for (JavaClass clazz : classes) {
			clazz.getDirectDependenciesFromSelf().stream()
				.map(dep -> dep.getTargetClass().getBaseComponentType())
				.filter(dep -> !dep.isPrimitive())
				.filter(dep -> !exclusions.excludesUse(dep.getName()))
				.filter(dep -> !isJavaPackage(dep.getPackageName()))
				.filter(dep -> findModuleForPackage(dep.getPackageName(), modules).isEmpty())
				.forEach(dep -> findJpmsModuleName(dep).ifPresent(used::add));
		}
		return used;
	}

	/**
	 * The JPMS module of a class, as JPMS itself would name it: its named module (JDK, module path), else the
	 * module of the jar or directory it is loaded from (its {@code module-info}, its {@code Automatic-Module-Name},
	 * or the name derived from the jar's file name). Empty if the class cannot be loaded or has no module name.
	 */
	private Optional<String> findJpmsModuleName(JavaClass javaClass) {
		return jpmsModuleByPackage.computeIfAbsent(javaClass.getPackageName(), p -> {
			try {
				Class<?> loaded = Class.forName(javaClass.getName(), false, ModuleAnalysis.class.getClassLoader());
				if (loaded.getModule().isNamed()) {
					return Optional.of(loaded.getModule().getName());
				}
				CodeSource codeSource = loaded.getProtectionDomain().getCodeSource();
				if (codeSource == null || codeSource.getLocation() == null) {
					return Optional.empty();
				}
				Path location = Path.of(codeSource.getLocation().toURI());
				return ModuleFinder.of(location).findAll().stream().findFirst()
					.map(ModuleReference::descriptor)
					.map(descriptor -> descriptor.name());
			} catch (ClassNotFoundException | LinkageError | URISyntaxException | FindException | SecurityException
					 | IllegalArgumentException notFound) {
				return Optional.empty();
			}
		});
	}

	/** Adds a class to the measure of its top-level type. */
	private static void measure(JavaClass clazz, ModuleInfo module, Map<String, TypeSize> types) {
		TypeSize size = types.computeIfAbsent(topLevelName(clazz), name -> new TypeSize(topLevel(clazz).getPackageName()));
		size.methods += (int) clazz.getMethods().stream()
			.filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
			.count();
		classFileBytes(clazz).ifPresent(bytes -> {
			size.bytes += bytes;
			size.bytesKnown = true;
		});
		if (clazz.getEnclosingClass().isEmpty()) {
			size.exception = clazz.isAssignableTo(Throwable.class);
			size.staticHelper = isStaticHelper(clazz, module);
		}
	}

	private static boolean isStaticHelper(JavaClass clazz, ModuleInfo module) {
		if (clazz.isInterface() || clazz.isEnum() || clazz.isRecord()) {
			return false;
		}
		List<JavaMethod> methods = clazz.getMethods().stream()
			.filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
			.toList();
		return !methods.isEmpty()
			&& methods.stream().allMatch(method -> method.getModifiers().contains(JavaModifier.STATIC))
			&& clazz.getFields().stream()
				.filter(field -> !field.getModifiers().contains(JavaModifier.SYNTHETIC))
				.allMatch(field -> field.getModifiers().contains(JavaModifier.STATIC))
			&& clazz.getConstructors().stream()
				.allMatch(constructor -> constructor.getModifiers().contains(JavaModifier.PRIVATE))
			// A static factory, such as a module's entry point, returns the module's own types
			&& methods.stream().noneMatch(method ->
				module.contains(method.getRawReturnType().getBaseComponentType().getPackageName()));
	}

	private static ModuleDescription describe(ModuleInfo module, Map<String, TypeSize> types) {
		List<TypeDescription> described = types.entrySet().stream()
			.map(type -> new TypeDescription(
				type.getKey(),
				type.getValue().packageName,
				isPackageExported(type.getValue().packageName, module),
				type.getValue().methods,
				type.getValue().bytesKnown ? OptionalLong.of(type.getValue().bytes) : OptionalLong.empty(),
				type.getValue().exception,
				type.getValue().staticHelper))
			.toList();
		return new ModuleDescription(module.name(), module.packageName(), List.of(module.exports()),
			List.of(module.requires()), described);
	}

	private static OptionalLong classFileBytes(JavaClass clazz) {
		Optional<URI> location = clazz.getSource().map(source -> source.getUri());
		if (location.isEmpty()) {
			return OptionalLong.empty();
		}
		try {
			if ("file".equals(location.get().getScheme())) {
				return OptionalLong.of(Files.size(Path.of(location.get())));
			}
			// A class in a jar: its size is what its entry holds
			try (InputStream content = location.get().toURL().openStream()) {
				return OptionalLong.of(content.transferTo(OutputStream.nullOutputStream()));
			}
		} catch (IOException | IllegalArgumentException | SecurityException unreadable) {
			return OptionalLong.empty();
		}
	}

	private static JavaClass topLevel(JavaClass clazz) {
		JavaClass current = clazz;
		while (current.getEnclosingClass().isPresent()) {
			current = current.getEnclosingClass().get();
		}
		return current;
	}

	private static String topLevelName(JavaClass clazz) {
		return topLevel(clazz).getName();
	}

	private static boolean isJavaPackage(String packageName) {
		return packageName.equals("java") || packageName.startsWith("java.");
	}

	private static Optional<ModuleInfo> findModuleForPackage(String packageName, List<ModuleInfo> modules) {
		// The most specific module (longest matching package name)
		return modules.stream()
			.filter(module -> module.contains(packageName))
			.reduce((m1, m2) -> m1.packageName().length() > m2.packageName().length() ? m1 : m2);
	}

	private static boolean isPackageExported(String packageName, ModuleInfo module) {
		for (String export : module.exports()) {
			if (export.equals(packageName)) {
				return true;
			}
			// "." is the module's own package
			if (export.equals(".") && packageName.equals(module.packageName())) {
				return true;
			}
			if (export.endsWith("*") && packageName.startsWith(export.substring(0, export.length() - 1))) {
				return true;
			}
		}
		return false;
	}

	private static List<ModuleInfo> findAllModules(List<JavaClass> classes) {
		List<ModuleInfo> modules = new ArrayList<>();
		for (JavaClass javaClass : classes) {
			if (javaClass.getSimpleName().equals("package-info") && javaClass.isAnnotatedWith(Module.class)) {
				Module annotation = javaClass.getAnnotationOfType(Module.class);
				modules.add(new ModuleInfo(annotation.name(), javaClass.getPackageName(), annotation.exports(),
					annotation.requires()));
			}
		}
		modules.sort(Comparator.comparing(ModuleInfo::name).thenComparing(ModuleInfo::packageName));
		return modules;
	}

	private static List<ExternalModuleInfo> findAllExternalModules(List<JavaClass> classes) {
		List<ExternalModuleInfo> externalModules = new ArrayList<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getSimpleName().equals("package-info")) {
				continue;
			}
			// A single @ExternalModule is stored as is, repeated ones inside their @ExternalModules container
			javaClass.tryGetAnnotationOfType(ExternalModule.class)
				.ifPresent(external -> externalModules.add(toExternalModuleInfo(external)));
			javaClass.tryGetAnnotationOfType(ExternalModules.class)
				.ifPresent(container -> Arrays.stream(container.value())
					.forEach(external -> externalModules.add(toExternalModuleInfo(external))));
		}
		externalModules.sort(Comparator.comparing(ExternalModuleInfo::name));
		return externalModules;
	}

	private static ExternalModuleInfo toExternalModuleInfo(ExternalModule annotation) {
		return new ExternalModuleInfo(annotation.name(), annotation.packages());
	}
}
