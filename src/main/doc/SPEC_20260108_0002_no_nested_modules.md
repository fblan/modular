# SPEC_20260108_0002: Nested Module Prevention Rule

**Date**: 2026-01-08
**Status**: In Progress
**Author**: Project Team
**Version**: 1.0
**Related**: SPEC_20260108_0001_module_annotation.md

---

## Part 1: Feature Description

### Overview
This feature enforces a strict architectural rule that prevents module hierarchies by forbidding a module from containing another module within its package structure. This ensures a flat module architecture where all modules are peers at the same conceptual level.

### Purpose
The nested module prevention rule aims to:
- Enforce a flat module structure to reduce architectural complexity
- Prevent ambiguity in module boundaries and ownership
- Simplify dependency management between modules
- Ensure clear and unambiguous module exports
- Avoid confusion about which module's rules apply to a given package

### Problem Statement

#### Without This Rule
Developers could create nested module structures like:
```
com.example.moduleA (@Module)
  └── com.example.moduleA.submodule (@Module)
```

This creates several issues:
1. **Ambiguous Boundaries**: Which module owns the nested package?
2. **Export Confusion**: If moduleA exports ".", does it include submodule?
3. **Dependency Complexity**: Inter-module dependencies become harder to track
4. **Verification Overhead**: Rule checking becomes exponentially complex

#### With This Rule
All modules must be independent, peer-level packages:
```
com.example.moduleA (@Module)
com.example.moduleB (@Module)
com.example.moduleC (@Module)
```

### Business Rules

#### Rule Definition
**A package annotated with `@Module` MUST NOT contain any sub-packages that are also annotated with `@Module`.**

#### Validation Criteria
- **Valid**: Module package has no `@Module` annotations in any descendant packages
- **Invalid**: Module package contains at least one sub-package with `@Module` annotation

#### Scope
- Applies to all packages scanned by `ModularVerifier`
- Checked recursively through entire package hierarchy
- Violation detected at any nesting level (not just immediate children)

### Use Cases

#### Use Case 1: Valid Flat Module Structure
**Scenario**: Multiple independent modules in different package roots

```
org.example.authentication (@Module: name="auth")
org.example.billing (@Module: name="billing")
org.example.reporting (@Module: name="reporting")
```

**Expected Result**: `Valid` - All modules are peers

#### Use Case 2: Invalid Direct Nesting
**Scenario**: Parent module contains child module

```
org.example.core (@Module: name="core")
  └── org.example.core.security (@Module: name="security")
```

**Expected Result**: `Invalid` - security module is nested within core module

#### Use Case 3: Invalid Deep Nesting
**Scenario**: Module nested multiple levels deep

```
org.example.app (@Module: name="app")
  └── org.example.app.services
      └── org.example.app.services.impl (@Module: name="impl")
```

**Expected Result**: `Invalid` - impl module is nested within app module (even through intermediate packages)

#### Use Case 4: Valid Sibling Modules
**Scenario**: Modules in sibling packages

```
org.example.modules.auth (@Module: name="auth")
org.example.modules.billing (@Module: name="billing")
```

**Expected Result**: `Valid` - Modules share common parent but neither contains the other

### Error Reporting Requirements

When a nested module is detected, the `Invalid` result should provide:
1. **Parent Module**: Name and package of the containing module
2. **Nested Module**: Name and package of the contained module
3. **Nesting Level**: How many package levels deep the nesting occurs
4. **Remediation Hint**: Suggestion to move nested module to peer level

Example error format:
```
Module hierarchy violation detected:
  Parent: 'core' (org.example.core)
  Nested: 'security' (org.example.core.security)
  Level: 1 (direct child)
  Suggestion: Move 'security' module to org.example.security
```

### Benefits
1. **Simplicity**: Flat structure is easier to understand and maintain
2. **Clarity**: Each module has clear, non-overlapping boundaries
3. **Scalability**: Easy to add new modules without restructuring
4. **Tool Support**: IDEs and build tools handle flat structures better
5. **Migration**: Easier to extract modules to separate artifacts later

---

## Part 2: Implementation Description

### Algorithm Design

#### High-Level Flow
1. Scan all packages provided to `ModularVerifier`
2. Identify all packages annotated with `@Module`
3. For each module, check all descendant packages
4. If any descendant has `@Module` annotation, record violation
5. Return `Valid` if no violations, `Invalid` otherwise

#### Pseudo-Code
```java
Result verify() {
    List<ModuleInfo> modules = findAllModules(packages);

    for (ModuleInfo parent : modules) {
        for (ModuleInfo candidate : modules) {
            if (candidate.isDescendantOf(parent)) {
                return new Invalid(
                    parent.name(),
                    candidate.name(),
                    parent.packageName(),
                    candidate.packageName(),
                    calculateNestingLevel(parent, candidate)
                );
            }
        }
    }

    return new Valid();
}
```

#### Package Hierarchy Detection
```java
boolean isDescendantOf(String parentPackage, String candidatePackage) {
    // candidate is descendant if it starts with parent + "."
    // e.g., "com.example.core.security".startsWith("com.example.core.")
    return candidatePackage.startsWith(parentPackage + ".");
}
```

### ArchUnit Integration

#### Scanning for @Module Annotations
```java
JavaClasses classes = new ClassFileImporter().importPackages(packages);

// Find all package-info classes with @Module annotation
Set<JavaClass> packageInfoClasses = classes.stream()
    .filter(clazz -> clazz.getSimpleName().equals("package-info"))
    .filter(clazz -> clazz.isAnnotatedWith(Module.class))
    .collect(Collectors.toSet());
```

#### Extracting Module Information
```java
for (JavaClass packageInfo : packageInfoClasses) {
    Module annotation = packageInfo.getAnnotationOfType(Module.class);
    String moduleName = annotation.name();
    String packageName = packageInfo.getPackageName();
    String[] exports = annotation.exports();

    // Store for hierarchy checking
    modules.add(new ModuleInfo(moduleName, packageName, exports));
}
```

### Enhanced Result Types

#### Current Implementation
```java
public sealed interface Result permits Valid, Invalid {}
public record Valid() implements Result {}
public record Invalid() implements Result {}
```

#### Proposed Enhancement for Detailed Errors
```java
public record Invalid(
    String parentModuleName,
    String nestedModuleName,
    String parentPackage,
    String nestedPackage,
    int nestingLevel
) implements Result {

    public String formatError() {
        return String.format(
            "Module hierarchy violation detected:%n" +
            "  Parent: '%s' (%s)%n" +
            "  Nested: '%s' (%s)%n" +
            "  Level: %d (%s)%n" +
            "  Suggestion: Move '%s' module to %s",
            parentModuleName, parentPackage,
            nestedModuleName, nestedPackage,
            nestingLevel, nestingLevel == 1 ? "direct child" : "indirect descendant",
            nestedModuleName, suggestPeerPackage(nestedPackage, parentPackage)
        );
    }
}
```

### Implementation Location
- **File**: `verification/src/main/java/org/asymetryk/modular/verification/ModularVerifier.java`
- **Method**: `verify()` at line 26
- **Current Status**: Stub implementation (returns `Invalid()`)

### Test Data Structure

#### Existing Test Modules

**Valid Module**: `org.asymetryk.modulyth.testedmodules.validisolatedmod`
- File: `verification/src/test/java/org/asymetryk/modulyth/testedmodules/validisolatedmod/package-info.java`
- Annotation: `@Module(name="validisolatedmodule", exports = {"."})`
- Classes: `ValidModule.java`

**Invalid Hierarchy**: `org.asymetryk.modulyth.testedmodules.invalidhierarchy`
- Parent: `org.asymetryk.modulyth.testedmodules.invalidhierarchy/package-info.java`
  - Annotation: `@Module(name="testmodule", exports = {})`
  - Classes: `RootModule.java`
- Nested: `org.asymetryk.modulyth.testedmodules.invalidhierarchy.submodule/package-info.java`
  - Annotation: `@Module(name="module", exports = {})`
  - Classes: `SubModule.java`

---

## Unit Tests

### Test Suite: `ModularVerifierTest.java`

#### Test 1: Valid Isolated Module (Existing)
```java
@Test
void testing_an_isolated_module_should_return_success() {
    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.validisolatedmod"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
}
```

**Purpose**: Verify that a single module without nested modules is valid
**Test Data**: `validisolatedmod` package with one `@Module` annotation
**Expected**: `Valid` result

---

#### Test 2: Invalid Module Hierarchy (Existing - Needs Enhancement)
```java
@Test
void testing_a_module_hierarchy_should_return_an_error() {
    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.invalidhierarchy"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Invalid.class);
    ModularVerifier.Invalid invalid = (ModularVerifier.Invalid) result;

    // Enhanced assertions
    assertThat(invalid.parentModuleName()).isEqualTo("testmodule");
    assertThat(invalid.nestedModuleName()).isEqualTo("module");
    assertThat(invalid.parentPackage())
        .isEqualTo("org.asymetryk.modulyth.testedmodules.invalidhierarchy");
    assertThat(invalid.nestedPackage())
        .isEqualTo("org.asymetryk.modulyth.testedmodules.invalidhierarchy.submodule");
    assertThat(invalid.nestingLevel()).isEqualTo(1);
}
```

**Purpose**: Verify that nested module hierarchy is detected and reported
**Test Data**: `invalidhierarchy` parent module containing `submodule` child module
**Expected**: `Invalid` result with detailed error information

---

#### Test 3: Multiple Independent Modules (New Test Required)
```java
@Test
void testing_multiple_independent_modules_should_return_success() {
    // Test data structure needed:
    // org.asymetryk.modulyth.testedmodules.multimodule.moduleA (@Module)
    // org.asymetryk.modulyth.testedmodules.multimodule.moduleB (@Module)
    // org.asymetryk.modulyth.testedmodules.multimodule.moduleC (@Module)

    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.multimodule"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
}
```

**Purpose**: Verify that multiple sibling modules are valid
**Test Data Required**: Create `multimodule` package with three peer-level modules
**Expected**: `Valid` result

**Test Data Files to Create**:
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multimodule/moduleA/package-info.java`
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multimodule/moduleB/package-info.java`
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multimodule/moduleC/package-info.java`

---

#### Test 4: Deep Nesting Detection (New Test Required)
```java
@Test
void testing_deeply_nested_module_should_return_error() {
    // Test data structure needed:
    // org.asymetryk.modulyth.testedmodules.deepnesting (@Module: "root")
    // org.asymetryk.modulyth.testedmodules.deepnesting.level1.level2.nested (@Module: "nested")

    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.deepnesting"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Invalid.class);
    ModularVerifier.Invalid invalid = (ModularVerifier.Invalid) result;

    assertThat(invalid.parentModuleName()).isEqualTo("root");
    assertThat(invalid.nestedModuleName()).isEqualTo("nested");
    assertThat(invalid.nestingLevel()).isGreaterThan(1);
}
```

**Purpose**: Verify that deeply nested modules (not just direct children) are detected
**Test Data Required**: Create `deepnesting` package with module nested 3+ levels deep
**Expected**: `Invalid` result with nesting level > 1

**Test Data Files to Create**:
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/deepnesting/package-info.java`
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/deepnesting/level1/level2/nested/package-info.java`

---

#### Test 5: Module with Non-Module Sub-Packages (New Test Required)
```java
@Test
void testing_module_with_regular_subpackages_should_return_success() {
    // Test data structure needed:
    // org.asymetryk.modulyth.testedmodules.withsubpackages (@Module)
    // org.asymetryk.modulyth.testedmodules.withsubpackages.util (no @Module)
    // org.asymetryk.modulyth.testedmodules.withsubpackages.model (no @Module)

    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.withsubpackages"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
}
```

**Purpose**: Verify that sub-packages without `@Module` annotation are allowed
**Test Data Required**: Create module with regular sub-packages (no @Module annotation)
**Expected**: `Valid` result

**Test Data Files to Create**:
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/withsubpackages/package-info.java` (with @Module)
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/withsubpackages/util/SomeUtil.java` (no package-info)
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/withsubpackages/model/SomeModel.java` (no package-info)

---

#### Test 6: Multiple Nested Violations (New Test Required)
```java
@Test
void testing_module_with_multiple_nested_modules_should_report_first_violation() {
    // Test data structure needed:
    // org.asymetryk.modulyth.testedmodules.multinesting (@Module: "parent")
    // org.asymetryk.modulyth.testedmodules.multinesting.child1 (@Module: "child1")
    // org.asymetryk.modulyth.testedmodules.multinesting.child2 (@Module: "child2")

    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.multinesting"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Invalid.class);
    ModularVerifier.Invalid invalid = (ModularVerifier.Invalid) result;

    assertThat(invalid.parentModuleName()).isEqualTo("parent");
    // Should report one of the children (implementation-dependent which one)
    assertThat(invalid.nestedModuleName()).isIn("child1", "child2");
}
```

**Purpose**: Verify behavior when multiple nested modules exist
**Test Data Required**: Create parent module with two child modules
**Expected**: `Invalid` result (at least one violation reported)

**Test Data Files to Create**:
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multinesting/package-info.java`
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multinesting/child1/package-info.java`
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/multinesting/child2/package-info.java`

---

#### Test 7: Empty Package Scan (New Test Required)
```java
@Test
void testing_scan_with_no_modules_should_return_success() {
    // Test data: package with no @Module annotations
    ModularVerifier modularVerifier = new ModularVerifier(
        "org.asymetryk.modulyth.testedmodules.nomodules"
    );
    ModularVerifier.Result result = modularVerifier.verify();

    assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
}
```

**Purpose**: Verify that scanning packages without any modules is valid (no violations possible)
**Test Data Required**: Create package with regular classes but no `@Module` annotations
**Expected**: `Valid` result

**Test Data Files to Create**:
- `verification/src/test/java/org/asymetryk/modulyth/testedmodules/nomodules/RegularClass.java` (no package-info)

---

### Test Data Summary

#### Existing Test Data
- ✅ `validisolatedmod` - Single isolated module
- ✅ `invalidhierarchy` - Parent module with nested child

#### New Test Data Required
- ⬜ `multimodule` - Three sibling modules (moduleA, moduleB, moduleC)
- ⬜ `deepnesting` - Root module with deeply nested module (3+ levels)
- ⬜ `withsubpackages` - Module with regular sub-packages (no @Module)
- ⬜ `multinesting` - Parent module with multiple nested children
- ⬜ `nomodules` - Package with no modules

### Test Coverage Goals
- ✅ Basic validation (isolated module)
- ✅ Basic violation (direct nesting)
- ⬜ Multiple valid modules
- ⬜ Deep nesting detection
- ⬜ Sub-packages without @Module
- ⬜ Multiple violations
- ⬜ Empty scan

**Coverage Target**: 7/7 tests (100% of use cases)

---

## Implementation Checklist

- [ ] Update `Invalid` record to include violation details
- [ ] Implement module discovery in `verify()` method
- [ ] Implement hierarchy checking algorithm
- [ ] Create test data for new test cases
- [ ] Implement all 7 unit tests
- [ ] Add integration test with real-world package structure
- [ ] Document error messages and remediation suggestions
- [ ] Update SPEC_20260108_0001 if API changes are needed