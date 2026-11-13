# Modular Java Project

## Overview
This project provides a simple, annotation-based approach to modularize Java applications. It uses package-level annotations and ArchUnit-based verification to enforce modular boundaries and prevent unwanted dependencies between modules.

---
**Note for Claude Code**: As this project evolves, you should proactively analyze changes to the codebase and propose updates to this CLAUDE.md file. This includes:
- New features, classes, or modules added
- Changes to the API or verification logic
- New architectural rules or constraints
- Additional dependencies or configuration
- Updated development status or completion of stubbed implementations
- New test cases that reveal additional design rules
- Any breaking changes or migration notes

Regularly suggest improvements to keep this documentation accurate and helpful.

**Feature Specifications**: When a new feature specification is created in `src/main/doc/SPEC_*.md`, you should:
1. Review the specification for completeness and clarity
2. Propose amendments or improvements to the specification if needed
3. Ask the user if they want to proceed with implementation
4. If implementing, follow the specification exactly as documented
5. Update test cases as specified in the document
6. Mark the specification status as "Implemented" when complete

**Git Workflow**: Never commit directly to `main`. For every change:
1. Start from an up-to-date `main` and create a feature branch: `git checkout main && git pull && git checkout -b feature/<short-kebab-description>`
2. Use one branch per GitHub issue, and reference it in the commit message (`Closes #<n>`)
3. Run `mvn clean install` and make sure all tests pass before committing
4. Push the branch and open a pull request into `main`

---

## Technology Stack
- **Java Version**: 21
- **Build Tool**: Maven
- **Key Dependencies**:
  - ArchUnit 1.4.1 (architecture testing)
  - JUnit Jupiter 5.13.4 (testing)
  - AssertJ 3.27.6 (assertions)

## Project Structure

### Multi-Module Maven Project
```
modular/
├── annotations/            # Annotations module
│   └── src/main/java/
│       └── org/asymetryk/modular/annotations/
│           ├── Module.java          # @Module annotation definition
│           ├── ExternalModule.java  # @ExternalModule: virtual module for packages outside modules
│           └── ExternalModules.java # Container for repeated @ExternalModule
│
├── specification/         # EARS requirements on modules (annotation + its own processor)
│   └── src/main/java/
│       └── org/asymetrik/modular/specification/
│           ├── Req.java, Reqs.java              # @Req (repeatable) and its container
│           ├── TestReq.java                     # @TestReq: links a test to requirement ids
│           └── processor/RequirementProcessor.java
│
├── verification/          # Verification module
│   └── src/main/java/
│       └── org/asymetryk/modular/verification/
│           ├── ModuleAnalysis.java   # ArchUnit-based model: modules, dependencies, all violations
│           └── ModularVerifier.java  # First violation of the model, and the violation types
│
├── report/                # HTML module report, built on ModuleAnalysis
│   └── src/main/
│       ├── java/org/asymetrik/modular/report/
│       │   ├── ModularReport.java   # Entry point: of(...), findings(), html(), writeTo(Path), main
│       │   ├── Finding.java         # A finding: level (ERROR, WARNING, HINT), kind, title, details, modules
│       │   ├── ModuleGraph.java     # Dependencies between modules, and the cycles
│       │   ├── Findings.java        # Computes the findings
│       │   ├── DiagramLayout.java   # Positions of the diagram's boxes and arrows (layers)
│       │   ├── DiagramSvg.java      # The inline SVG of the diagram
│       │   ├── ReportPage.java      # The HTML page
│       │   └── Text.java            # Escaping, plurals, numbers, sizes
│       └── resources/org/asymetrik/modular/report/   # report.css and report.js, put inline in the page
│
└── sample-fizzbuzz/       # Sample FizzBuzz app to play with modular (not published)
    └── src/main/java/
        └── org/asymetrik/modular/sample/fizzbuzz/
            ├── rules/     # @Module "rules" (exports "."; rules.internal is hidden)
            ├── engine/    # @Module "engine" (exports ".", requires "rules")
            └── app/       # @Module "app" (exports nothing, requires "engine", "rules") - FizzBuzzApp main
```

## Core Concepts

### @Module Annotation
Located in `annotations/src/main/java/org/asymetryk/modular/annotations/Module.java`

- **Target**: Package-level annotation (ElementType.PACKAGE)
- **Attributes**:
  - `name()`: String - The module name
  - `exports()`: String[] - Array of exported packages/patterns
  - `requires()`: String[] (default `{}`) - Names of the modules, virtual modules or JPMS modules (e.g. `java.desktop`, `jakarta.validation`) this module may use (not transitive)

**Usage**: Applied in `package-info.java` files:
```java
@Module(name="mymodule", exports = {"."}, requires = {"othermodule"})
package com.example.mypackage;

import org.asymetryk.modular.annotations.Module;
```

### @ExternalModule Annotation (virtual modules)
Located in `annotations/src/main/java/org/asymetrik/modular/annotations/ExternalModule.java`

- **Target**: Package-level, repeatable (container: `@ExternalModules`), on any `package-info.java`
- **Attributes**: `name()` (shares the namespace of module names) and `packages()` (each covers its sub-packages)
- Groups packages outside every `@Module` (libraries, `javax.*`, own packages without `@Module`) under a name that modules can list in `requires`. Everything in it is exported. When several cover a package, the most specific one wins.

```java
@ExternalModule(name = "inject", packages = {"jakarta.inject"})
@ExternalModule(name = "slf4j", packages = {"org.slf4j"})
package com.example.modules;
```

### @Req Annotation (specification module)
Located in `specification/src/main/java/org/asymetrik/modular/specification/Req.java`

- **Target**: Package-level, repeatable (container: `@Reqs`), only on a `@Module` package
- **Attributes**: `value()` - one requirement sentence in EARS format; `id()` (default `""`) - unique when set
- **Checked by** `RequirementProcessor` (same jar, enabled through `annotationProcessorPaths`): `@Req` is next to
  `@Module`, the sentence is EARS (ubiquitous, `When`, `While`, `If …, then`, `Where`, or combined clauses), its
  subject is the module (`the <module name> shall`, ignoring case), and ids are unique within the compilation
- Reads `@Module` by qualified name: `specification` does not depend on `annotations`
- Writes `META-INF/modular/requirements.properties` (`<id>=<module>|<sentence>`, sorted) to the class output when
  compiling `@Req`s with ids; on an incremental build, entries of modules not recompiled are kept

### @TestReq Annotation (specification module)
- **Target**: test methods and classes, `RUNTIME` retention; `value()` - requirement ids
- **Checked by** `RequirementProcessor` when the tests compile, using the index on the class path: unknown id is
  an error (on the test), a requirement with an id but no `@TestReq` is a warning (only in compilations that
  contain a `@TestReq`)

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
@Req("While the upper bound is below 1, the engine shall say nothing.")
package com.example.engine;
```

### ModularVerifier
Located in `verification/src/main/java/org/asymetryk/modular/verification/ModularVerifier.java`

- **Purpose**: Verifies that package structures comply with modular constraints
- **Technology**: Uses ArchUnit's ClassFileImporter to analyze bytecode
- **Result Types**:
  - `Valid`: Module structure is correct
  - `Invalid`: Module structure violates constraints (the first violation of `ModuleAnalysis.violations()`);
    every violation has `formatError()`

**Usage**:
```java
ModularVerifier verifier = new ModularVerifier("org.example.package");
ModularVerifier.Result result = verifier.verify();
// Result is either Valid or Invalid (sealed interface)
```

### ModuleAnalysis
Located in `verification/src/main/java/org/asymetrik/modular/verification/ModuleAnalysis.java`

- **Purpose**: the public model behind `ModularVerifier`, and the data of the module report (`report` module)
- **Built by** `ModuleAnalysis.of("com.example")`, or `of(ClassScope.WITHOUT_TESTS, packages)` to leave test classes out
- **Exclusions** (`of(scope, packages, new Exclusions(classes, uses))`): class-name patterns (`*` stops at
  `.`, `**` does not) for code a framework generates or weaves in, which the processor never sees. Excluded classes
  are not measured, analysed or unassigned (a nested class goes with its top-level class); excluded uses are no
  dependency and no violation. `exclusions()` returns them
- `modules()`: name, package, exports, requires and top-level types (package, exported or not, methods, class file
  bytes, whether it is an exception, whether it is a class of static helpers, public methods, lines of code and the
  lines of each method with code, read from the class files' line numbers by `ClassFileLines`, a lambda counting with
  the method that holds it; nested classes count with their top-level type, `package-info` is not a type)
- `externalModules()`: the `@ExternalModule` declarations
- `dependencies()`: one per (module, target), with the (consumer class, used class) pairs behind it, and whether the
  consumer creates instances of the used class (a constructor call; `super(...)` is not one). The target is a
  `MODULE`, `EXTERNAL_MODULE`, `JPMS_MODULE` or `UNMANAGED` package. Forbidden uses are listed too; `java.base` and
  uses inside a module are not. Another JDK module (`java.net.http`) is a `JPMS_MODULE` target that breaks no rule
- `violations()`: every `ModularVerifier.Invalid`, in the order nesting, names (duplicates, then unknown `requires`),
  dependencies. `ModularVerifier.verify()` returns the first one
- `unassignedClasses()`: scanned top-level types in no module
- Everything is sorted (modules by name then package, classes by name), so the result is the same on every run
- There is no rule against cycles between modules: a cycle shows in `dependencies()`, not in `violations()`

### ModularReport (report module)
Located in `report/src/main/java/org/asymetrik/modular/report/ModularReport.java`

- **Purpose**: one self-contained `index.html` to check that the modules are sound: sizes, dependencies, diagram,
  findings. `ModularReport.of("com.example").writeTo(Path.of("target/modular-report"))`, from a test or with `main`
  (`[--exclude-class=<pattern>]... [--exclude-use=<pattern>]... <output directory> <package>...`), since it needs the
  class path of the application. With exclusions, the masthead says what was left out
- `of(String...)` / `of(List)` leave test classes out; `of(ModuleAnalysis)` reports on any analysis
- `findings()`: violations (`ERROR`), cycles, classes in no module and large modules (`WARNING`; more than
  `Findings.MAX_TYPES`, 50, types), `requires` not seen in use and
  exported packages not used (`HINT`, can be false alarms as only bytecode is read; never given for `java.base`), and
  design hints (`HINT`): large types (more than `Findings.MAX_PUBLIC_METHODS`, 20, public methods), large
  methods (more than `Findings.MAX_LINES`, 75, lines of code), leaky APIs (from 0.1.3, without the
  uses that are only annotations: `ClassUse.annotationsOnly()`), borrowed
  exceptions and exported helpers. Each finding names the modules to look at (`Finding.modules()`): the page colors
  them by their most serious finding (diagram box, modules table, module section with links to the findings)
- **Page rules**: no network reference, no JavaScript library, complete without JavaScript (the script only
  highlights a module's arrows), light and dark themes from CSS tokens in `report.css`, every name escaped with
  `Text.escape`, and the same page for the same classes apart from the generation time
- **Diagram**: inline SVG laid out by `DiagramLayout` in Java. A module is below the modules that use it; an arrow
  outside a cycle always points down; cycle arrows are red. Virtual and JPMS modules are not drawn
- The report never fails a build, and has no section on requirements yet (see the spec's out of scope)
- To look at a report while working on it: `sample-fizzbuzz/target/modular-report/index.html` after a build

## Design Rules

Based on test cases in `verification/src/test/java/`:

1. **Valid Isolated Module**: A single module without nested submodules is valid
2. **Invalid Module Hierarchy**: Nested module structures (modules within modules) are invalid
3. **Exports**: A module may only use classes from packages another module exports
4. **Requires**: A module may only use the modules listed in its `requires` (not transitive)
5. **Unique Names**: Module names must be unique, and every name in `requires` must exist (the processor only checks uniqueness, as an incremental build does not see every module)
6. **External Packages**: Outside modules, only `java.*` is free. Any other package must be required through its owner, resolved in this order: `@Module`, `@ExternalModule`, then its JPMS module name (named module, else the class path jar's `module-info`, `Automatic-Module-Name` or file name). With no owner, it is an unmanaged dependency

## Build Commands

```bash
# Build the entire project
mvn clean install

# Build specific module
mvn clean install -pl annotations
mvn clean install -pl verification
mvn clean install -pl report
mvn clean install -pl specification

# Run tests
mvn test

# Run tests in specific module
mvn test -pl verification
```

## Package Naming Convention
- Production code: `org.asymetryk.modular.*`
- Test modules: `org.asymetryk.modulyth.testedmodules.*`

## Development Status
- Version: 0.1.3-SNAPSHOT (last release: 0.1.2)
- GroupId: org.asymetrik.modular
- Published modules: `annotations`, `processor`, `verification`, `report`, `specification` (`sample-fizzbuzz` is excluded)
- Not released yet (from 0.1.3): `ClassUse.annotationsOnly()`, and leaky APIs that leave out uses only in
  annotations. `README.md` and `SKILL.md` describe the released version and mark these "from 0.1.3": drop the
  marks, and move their versions to 0.1.3, when it is released

## Specifications
- **Module Annotation Feature**: `src/main/doc/SPEC_20260108_0001_module_annotation.md`
  - Complete feature description and implementation details
  - Covers @Module annotation design, usage patterns, and verification rules
  - Documents current implementation status and future enhancements

- **Nested Module Prevention Rule**: `src/main/doc/SPEC_20260108_0002_no_nested_modules.md`
  - Architectural rule preventing module hierarchies
  - Enforces flat module structure (no modules within modules)
  - Includes 7 comprehensive unit tests (2 existing, 5 new required)
  - Details implementation algorithm and error reporting requirements

- **Module Requires**: `src/main/doc/SPEC_20261019_0001_module_requires.md`
  - `requires` attribute: a module may only use the modules it requires (breaking change)
  - Duplicate and unknown module name checks

- **External Modules**: `src/main/doc/SPEC_20261019_0002_external_modules.md`
  - `@ExternalModule` virtual modules for packages outside modules; only `java.*` is free (breaking change)

- **JPMS Module Requires**: `src/main/doc/SPEC_20261019_0003_jpms_module_requires.md`
  - JPMS module names (JDK modules, class path jars) can be used in `requires` without `@ExternalModule`

- **Module Requirements (EARS)**: `src/main/doc/SPEC_20261019_0004_module_requirements.md`
  - New `specification` module: `@Req` EARS requirements next to `@Module`, checked by `RequirementProcessor`

- **Requirement Tests**: `src/main/doc/SPEC_20261019_0005_test_requirements.md`
  - `@TestReq` links tests to requirement ids; unknown ids are errors, untested requirements are warnings

- **Module Report**: `src/main/doc/SPEC_20261111_0001_module_report.md`
  - New `report` module: `ModularReport` writes an HTML page with sizes, dependencies, a diagram and findings

- **Generated Code and JDK Modules**: `src/main/doc/SPEC_20261112_0001_exclusions_and_jdk_modules.md`
  - `Exclusions` for generated and woven code; JDK modules other than `java.base` as dependencies

- **Design Hints**: `src/main/doc/SPEC_20261112_0002_design_hints.md`
  - Report hints for large types, leaky APIs, borrowed exceptions and exported helpers

- **Module Colors and Large Modules**: `src/main/doc/SPEC_20261112_0003_module_colors_and_large_modules.md`
  - Warn about modules much larger than the others; color the modules that have findings

- **Annotation Uses**: `src/main/doc/SPEC_20261112_0004_annotation_uses.md`
  - `ClassUse.annotationsOnly()`; leaky APIs leave out the uses that are only annotations

- **Size Limits**: `src/main/doc/SPEC_20261113_0001_size_limits.md`
  - Large modules in types, large types in public methods, large methods in lines of code; no more class file bytes

- **Module Order and Filter**: `src/main/doc/SPEC_20261113_0002_module_order_and_filter.md`
  - Modules sorted by most serious finding, then lines of code; a box filters them by name

- **Methods Without Line Numbers**: `src/main/doc/SPEC_20261113_0003_methods_without_line_numbers.md`
  - A method without line numbers (added by a framework) is left out, instead of making its type unknown

## Key Files to Know
- `README.md` - User documentation: setup, rules, requirements, errors and fixes. Keep it in sync with features
- `.claude-plugin/marketplace.json` - Claude Code marketplace `asymetrik`, listing the `modular` plugin
- `claude/plugins/modular/skills/modular/SKILL.md` - Skill teaching Claude to use modular in a user's project. Keep it in sync with features (rules, error messages, versions); bump `version` in `claude/plugins/modular/.claude-plugin/plugin.json` when it changes, and run `claude plugin validate .`
- `pom.xml` - Parent POM defining Java 21 and modules
- `annotations/pom.xml` - Annotations module POM (minimal, just annotation)
- `verification/pom.xml` - Verification module POM with ArchUnit dependency
- `specification/pom.xml` - Specification module POM (`@Req` + `RequirementProcessor`, `<proc>none</proc>` for its own sources)
- `sample-fizzbuzz/pom.xml` - Sample app POM; shows how to enable the processor via `annotationProcessorPaths` (required on JDK 23+), excluded from Maven Central publishing in the root POM
- `sample-fizzbuzz/README.md` - How to run the sample and experiments that trigger each violation
- `verification/src/main/java/org/asymetrik/modular/verification/ModuleAnalysis.java` - Main verification logic: the model and the rule checks
- `verification/src/main/java/org/asymetrik/modular/verification/ModularVerifier.java` - `verify()` and the `Result` / violation types
- `verification/src/test/java/org/asymetrik/modular/verification/ModuleAnalysisTest.java` - Tests of the model, on the `shop` (valid, with a cycle) and `broken` (three violations) fixtures, `exclusions` (generated and woven code), `jdkuse` (a JDK module), `traits` (exceptions, static helpers, instances created) and `annotated` (uses only in annotations)
- `verification/src/test/java/org/asymetryk/modular/verification/ModularVerifierTest.java` - Test cases showing intended behavior
- `report/pom.xml` - Report module POM (depends on `verification` only)
- `report/src/test/java/org/asymetrik/modular/report/` - Tests of the report, on the `testapp` (one of each finding, a module name to escape), `cleanapp` (no finding), `brokenapp` (unknown `requires`), `jdkapp` (JDK modules), `designapp` (one of each design hint) and `sizeapp` (a large module) fixtures
- `sample-fizzbuzz/src/test/java/org/asymetrik/modular/sample/fizzbuzz/ModuleReportTest.java` - Writes the sample's report to `target/modular-report/index.html`
- `src/main/doc/images/module-report.png` - Screenshot of the sample's report shown in `README.md`; take it again when the page changes

## Architecture Principles
This project enforces modular architecture by:
1. Using package-level annotations to declare module boundaries
2. Leveraging ArchUnit to verify architectural rules at build/test time
3. Preventing module hierarchies (flat module structure)
4. Controlling module exports to manage API visibility
5. Making dependencies between modules explicit with `requires`
6. Making external dependencies explicit with `@ExternalModule` (only `java.*` is free)