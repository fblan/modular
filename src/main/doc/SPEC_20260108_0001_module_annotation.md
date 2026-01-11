# SPEC_20260108_0001: Package-Level Module Annotation

**Date**: 2026-01-08
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0

---

## Part 1: Feature Description

### Overview
This feature introduces a declarative approach to modularize Java applications using package-level annotations. It enables developers to define module boundaries and control API visibility at the package level without requiring complex build configurations or JPMS (Java Platform Module System) adoption.

### Purpose
The module annotation system aims to:
- Provide a lightweight alternative to JPMS for application modularization
- Enable explicit declaration of module boundaries within a monolithic codebase
- Control which classes/packages are exposed as public API to other modules
- Support architectural verification through static analysis

### User Stories

**As a developer**, I want to:
- Declare a package as a module with a meaningful name
- Specify which sub-packages or classes are part of the module's public API
- Prevent other modules from accessing internal implementation details
- Verify at build time that module boundaries are respected

### Module Annotation Attributes

#### `name` (String, required)
- Defines a unique identifier for the module
- Should follow naming conventions (e.g., lowercase, descriptive)
- Used for documentation and verification purposes

#### `exports` (String[], required)
- Declares which packages/classes can be accessed by other modules
- Supports patterns:
  - `"."` - exports the current package
  - `""` (empty array) - exports nothing (fully isolated module)
  - Specific sub-package patterns (implementation-dependent)

### Use Cases

#### Use Case 1: Isolated Module
A module that exports its root package, allowing external access to all classes in that package.

```java
@Module(name="validisolatedmodule", exports = {"."})
package com.example.mymodule;
```

#### Use Case 2: Fully Internal Module
A module with no public API, completely isolated from other modules.

```java
@Module(name="internalmodule", exports = {})
package com.example.internal;
```

### Constraints
- Modules must be declared at the package level using `package-info.java`
- Module hierarchies (nested modules) are not permitted
- Each module should have a unique name within the application
- Export patterns must be valid package or class references

### Benefits
1. **Explicit Architecture**: Module boundaries are clearly visible in code
2. **Gradual Adoption**: Can be applied incrementally to existing codebases
3. **Build-Time Verification**: Architectural violations detected early in development
4. **Documentation**: Modules serve as self-documenting architectural components
5. **Refactoring Safety**: Clear contracts between modules prevent accidental coupling

---

## Part 2: Implementation Description

### Technical Architecture

The module annotation feature is implemented across two Maven modules:

1. **api** - Contains the `@Module` annotation definition
2. **verification** - Provides runtime/build-time verification using ArchUnit

### Component Details

#### 1. @Module Annotation (API Module)

**Location**: `api/src/main/java/org/asymetryk/modular/api/Module.java`

**Implementation**:
```java
@Target(ElementType.PACKAGE)
public @interface Module {
    String name();
    String[] exports();
}
```

**Design Decisions**:
- Uses `@Target(ElementType.PACKAGE)` to restrict usage to package declarations only
- No retention policy specified (defaults to `RetentionPolicy.CLASS`)
- Simple String-based attributes for maximum flexibility
- No validation logic in the annotation itself (delegated to verification layer)

#### 2. Package Declaration (Usage Pattern)

**Location**: `package-info.java` files at package roots

**Pattern**:
```java
@Module(name="modulename", exports = {"."})
package com.example.package;

import org.asymetryk.modular.api.Module;
```

**Requirements**:
- Must be placed in a `package-info.java` file
- Import statement required for the `@Module` annotation
- Package declaration must match the file's location

#### 3. ModularVerifier (Verification Module)

**Location**: `verification/src/main/java/org/asymetryk/modular/verification/ModularVerifier.java`

**Purpose**: Analyzes compiled bytecode to verify module constraints

**Key Components**:

##### Result Type System
```java
public sealed interface Result permits Valid, Invalid {}
public record Valid() implements Result {}
public record Invalid() implements Result {}
```

- Uses Java 21's sealed interfaces for type-safe result handling
- Provides explicit success/failure states
- Extensible for future error details in `Invalid` record

##### Verification API
```java
public ModularVerifier(final List<String> packages)
public ModularVerifier(final String... packages)
public Result verify()
```

- Accepts package names to analyze (supports both varargs and List)
- Returns a sealed `Result` type for pattern matching
- Uses ArchUnit's `ClassFileImporter` for bytecode analysis

**Implementation Status**:
- Core API: Implemented
- Verification logic: Stub (currently returns `Invalid()`)
- Integration point: `ModularVerifier.java:26-29`

#### 4. Verification Rules (Design Intent)

Based on test cases in `verification/src/test/java/org/asymetryk/modular/verification/ModularVerifierTest.java`:

##### Rule 1: Isolated Module Validation
- **Test**: `testing_an_isolated_module_should_return_success()`
- **Rule**: A package annotated with `@Module` without nested sub-modules is valid
- **Expected**: Returns `Valid` result

##### Rule 2: Hierarchy Prevention
- **Test**: `testing_a_module_hierarchy_shoud_return_an_error()`
- **Rule**: Nested module structures (module within module's sub-packages) are invalid
- **Expected**: Returns `Invalid` result

### Technology Stack

#### Dependencies
- **ArchUnit 1.4.1**: Bytecode analysis and architectural rule verification
- **JUnit Jupiter 5.13.4**: Test framework
- **AssertJ 3.27.6**: Fluent assertions

#### Build Configuration
- **Java Version**: 21
- **Maven**: Multi-module project structure
- **Compilation**: UTF-8 encoding, Java 21 source/target

### Integration Points

#### Build-Time Verification
The `ModularVerifier` can be integrated into:
- Maven build lifecycle (via maven-surefire-plugin tests)
- Custom Maven plugins for architectural validation
- CI/CD pipelines for continuous compliance checking

#### Usage Pattern
```java
ModularVerifier verifier = new ModularVerifier(
    "com.example.module1",
    "com.example.module2"
);
ModularVerifier.Result result = verifier.verify();

if (result instanceof ModularVerifier.Invalid invalid) {
    // Handle validation failures
    throw new ArchitectureViolationException("Modules violate constraints");
}
```

### Future Implementation Tasks

1. **Complete verification logic** in `ModularVerifier.verify()`:
   - Scan packages for `@Module` annotations
   - Build module dependency graph
   - Detect module hierarchies
   - Validate export constraints
   - Check for circular dependencies

2. **Enhance Invalid result** to include detailed error information:
   - List of violated rules
   - Affected packages/classes
   - Suggested remediation steps

3. **Add export pattern validation**:
   - Verify classes accessing exported APIs
   - Detect unauthorized cross-module dependencies

4. **Maven plugin integration**:
   - Standalone plugin for architectural verification
   - Configurable failure policies

### File References
- Annotation: `api/src/main/java/org/asymetryk/modular/api/Module.java`
- Verifier: `verification/src/main/java/org/asymetryk/modular/verification/ModularVerifier.java:26`
- Tests: `verification/src/test/java/org/asymetryk/modular/verification/ModularVerifierTest.java`
- Example (valid): `verification/src/test/java/org/asymetryk/modulyth/testedmodules/validisolatedmod/package-info.java`
- Example (invalid): `verification/src/test/java/org/asymetryk/modulyth/testedmodules/invalidhierarchy/package-info.java`