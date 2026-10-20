# Modular Java Project

## Overview
This project provides a simple, annotation-based approach to modularize Java applications. It uses package-level annotations and ArchUnit-based verification to enforce modular boundaries and prevent unwanted dependencies between modules.

---
**Note for Claude Code**: As this project evolves, you should proactively analyze changes to the codebase and propose updates to this claude.md file. This includes:
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
│           └── ModularVerifier.java  # ArchUnit-based verifier
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
  - `Invalid`: Module structure violates constraints

**Usage**:
```java
ModularVerifier verifier = new ModularVerifier("org.example.package");
ModularVerifier.Result result = verifier.verify();
// Result is either Valid or Invalid (sealed interface)
```

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
- Version: 0.1.0-SNAPSHOT (last release: 0.0.5)
- GroupId: org.asymetrik.modular
- Published modules: `annotations`, `processor`, `verification`, `specification` (`sample-fizzbuzz` is excluded)

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
- `verification/src/main/java/org/asymetryk/modular/verification/ModularVerifier.java:26` - Main verification logic (currently stubbed)
- `verification/src/test/java/org/asymetryk/modular/verification/ModularVerifierTest.java` - Test cases showing intended behavior

## Architecture Principles
This project enforces modular architecture by:
1. Using package-level annotations to declare module boundaries
2. Leveraging ArchUnit to verify architectural rules at build/test time
3. Preventing module hierarchies (flat module structure)
4. Controlling module exports to manage API visibility
5. Making dependencies between modules explicit with `requires`
6. Making external dependencies explicit with `@ExternalModule` (only `java.*` is free)