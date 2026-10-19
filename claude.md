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
│           └── Module.java    # @Module annotation definition
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
  - `requires()`: String[] (default `{}`) - Names of the modules this module may use (not transitive)

**Usage**: Applied in `package-info.java` files:
```java
@Module(name="mymodule", exports = {"."}, requires = {"othermodule"})
package com.example.mypackage;

import org.asymetryk.modular.annotations.Module;
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

## Build Commands

```bash
# Build the entire project
mvn clean install

# Build specific module
mvn clean install -pl annotations
mvn clean install -pl verification

# Run tests
mvn test

# Run tests in specific module
mvn test -pl verification
```

## Package Naming Convention
- Production code: `org.asymetryk.modular.*`
- Test modules: `org.asymetryk.modulyth.testedmodules.*`

## Development Status
- Version: 1.0-SNAPSHOT
- GroupId: org.asymetryk.modulyth
- Current implementation: ModularVerifier.verify() always returns Invalid() - implementation in progress

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

## Key Files to Know
- `pom.xml` - Parent POM defining Java 21 and modules
- `annotations/pom.xml` - Annotations module POM (minimal, just annotation)
- `verification/pom.xml` - Verification module POM with ArchUnit dependency
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