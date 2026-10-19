# SPEC_20261019_0001: Module Requires

**Date**: 2026-10-19
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #16](https://github.com/fblan/modular/issues/16), SPEC_20260108_0001_module_annotation.md

---

## Part 1: Feature Description

### Overview
A module must now declare which other modules it uses, through a new `requires` attribute on `@Module`. A class
may only depend on a class of another module if its own module requires that module. The existing exports rule
still applies on top: the used class must also be in an exported package of the required module.

### Purpose
- Make dependencies between modules explicit and visible in one place: the `package-info.java` of the module.
- Stop a module from silently starting to depend on another one just because that module exports something.
- Bring the model closer to JPMS (`requires` in `module-info.java`), with which most Java developers are familiar.

### Problem Statement

#### Without This Feature
```java
@Module(name = "billing", exports = {"."})
package com.example.billing;
```
Any class of `billing` can use the exported API of every other module. Nothing shows that `billing` depends on
`customers`, and a new dependency on `reporting` can appear without any change to `billing`'s declaration.

#### With This Feature
```java
@Module(name = "billing", exports = {"."}, requires = {"customers"})
package com.example.billing;
```
`billing` can use `customers` (its exported packages). Using any other module, for example `reporting`, is a
*Requires violation* until `"reporting"` is added to `requires`.

### Business Rules

1. **Requires rule**: a class of module A may depend on a class of module B, with B ≠ A, only if B's name is in A's
   `requires`.
2. **Not transitive**: if A requires B and B requires C, A must also require C to use it.
3. **Exports still apply**: when A requires B, A may only use classes from the packages B exports.
4. **Unique names**: modules are referenced by `name()`, so two modules may not have the same name.
5. **Known names**: every name in `requires` must be the name of an existing module.
6. **Optional attribute**: `requires` defaults to `{}`, meaning the module may use no other module.

When a class both uses a module it does not require and a package that module does not export, only the requires
violation is reported: adding the module to `requires` is the first fix, the export check follows.

## Part 2: Implementation

### `@Module`
```java
String[] requires() default {};
```

### `ModuleAnnotationProcessor` (compile time)
- `ModuleEntry` carries `requires`.
- Before the export check, each dependency to another module is checked against the consumer's `requires`. The
  error is reported on the consumer class, once per required module and class, with the same detection notes as
  export violations (import declaration, method body, string literal):
  `Requires violation: 'app' (...) uses '...Rules' from module 'rules' (...) without requiring it. Add "rules" to the requires of module 'app' ...`
- Duplicate module names among the modules of the round are reported on the second module's `package-info`.
- Unknown names in `requires` are **not** reported by the processor. In an incremental build (IntelliJ), a required
  module that is not being recompiled is absent from the round, and would be wrongly reported as unknown.

### `ModularVerifier` (ArchUnit, sees all classes)
New `Invalid` results:
- `RequiresViolation(consumerModuleName, consumerPackage, consumerClass, providerModuleName, providerPackage, usedClass)`
- `DuplicateModuleNameViolation(moduleName, firstPackage, secondPackage)`
- `UnknownRequiredModuleViolation(moduleName, modulePackage, requiredModuleName)`

Order of checks: nested modules, duplicate names, unknown required names, then requires and exports for each
dependency.

## Part 3: Tests

### Processor (`ModuleAnnotationProcessorTest`)
- Existing cross-module tests now declare `requires = {"producer"}` on the consumer, so they keep testing exports.
- `usingClassOfModuleNotRequired_producesRequiresViolationError`
- `usingNonExportedClassOfModuleNotRequired_producesOnlyRequiresViolationError`
- `requiresIsNotTransitive_producesRequiresViolationError`
- `severalTypesFromModuleNotRequired_produceSingleRequiresViolation`
- `duplicateModuleNames_producesDuplicateModuleNameError`
- `requiringModuleNotInCompilation_producesNoErrors`

### Verifier (`ModularVerifierTest`)
- `modulevalidtest` and `moduleusingnotexported` fixtures now require `moduleA`.
- `testing_module_using_class_of_module_it_does_not_require_should_return_error` (fixture `modulenotrequiring`)
- `testing_module_requiring_unknown_module_should_return_error` (fixture `unknownrequires`)
- `testing_two_modules_with_same_name_should_return_error` (fixture `duplicatenames`)

### Sample
`sample-fizzbuzz` declares `engine` requires `rules`, and `app` requires `engine` and `rules`.

## Migration (breaking change)
Every module that uses another module must add it to `requires`. Without it, the build fails with a requires
violation that names the module to add.
