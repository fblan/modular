# SPEC_20261019_0002: External Modules (Virtual Modules)

**Date**: 2026-10-19
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #18](https://github.com/fblan/modular/issues/18), SPEC_20261019_0001_module_requires.md

---

## Part 1: Feature Description

### Overview
Packages outside every `@Module` can be grouped into named *virtual modules* with a new `@ExternalModule`
annotation. A module uses a virtual module like any other module, by listing its name in `requires`. Outside the
modules, only `java.*` packages can be used freely: any other dependency must be in a virtual module the module
requires.

### Purpose
- Make external dependencies (libraries, `javax.*`, `jakarta.*`, ...) explicit, in the same place as the
  dependencies between modules.
- Control which module may use which library, e.g. only `infra` may use JDBC drivers or `org.slf4j`.
- Stop own packages without `@Module` from being a silent way around module boundaries.

### Problem Statement

#### Without This Feature
```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
package com.example.engine;

// in engine:
@Inject Rules rules;   // jakarta.inject.Inject: not checked at all
```
Any class outside the modules can be used by any module without being declared.

#### With This Feature
```java
@ExternalModule(name = "inject", packages = {"jakarta.inject"})
package com.example.modules;

@Module(name = "engine", exports = {"."}, requires = {"rules", "inject"})
package com.example.engine;
```
Without `"inject"` in `requires`, `@Inject` is a *Requires violation*. Without the `@ExternalModule`, it is an
*Unmanaged dependency*.

### Business Rules

1. **Free packages**: only `java` and `java.*` packages can be used without being in a module.
2. **Unmanaged dependency**: a class of a module may not depend on a class that is not in `java.*`, not in a
   `@Module` and not in an `@ExternalModule`. This includes own packages without `@Module`.
3. **Declaration**: `@ExternalModule(name, packages)` on any `package-info.java`, repeatable.
4. **Package coverage**: a listed package covers its sub-packages: `"org.slf4j"` covers `org.slf4j.event`.
5. **Most specific wins**: when several virtual modules cover a package, the one with the longest matching
   package owns it. A real `@Module` always wins over a virtual module.
6. **Requires**: a module may only use a virtual module it lists in `requires` (not transitive, as for modules).
7. **Everything exported**: a virtual module has no exports; all of its packages can be used.
8. **Names**: virtual module names share the namespace of module names: they must be unique across both, and
   `requires` may name either.

## Part 2: Implementation

### Annotations
```java
@Target(ElementType.PACKAGE)
@Repeatable(ExternalModules.class)
public @interface ExternalModule {
	String name();
	String[] packages();
}

@Target(ElementType.PACKAGE)
public @interface ExternalModules {
	ExternalModule[] value();
}
```

### `ModuleAnnotationProcessor`
- Supports `@Module`, `@ExternalModule` and `@ExternalModules`, and reads virtual modules with
  `getAnnotationsByType` (single and repeated declarations).
- For each dependency of a module class: `java.*` is skipped; a class of a module goes through the requires and
  exports checks; otherwise the most specific virtual module must be required (*Requires violation* naming the
  external module); otherwise *Unmanaged dependency*, once per package and class, with the package to declare.
- Duplicate names are checked across modules and virtual modules.
- `array.length` and `array.clone()` are skipped by the Tree API scan: javac models them as members of an internal
  `Array` class in no package, which would otherwise be reported as unmanaged.

### `ModularVerifier`
- Reads `@ExternalModule` and the `@ExternalModules` container from `package-info` classes.
- New `UnmanagedDependencyViolation(consumerModuleName, consumerPackage, consumerClass, usedClass, usedClassPackage)`.
- A virtual module that is not required gives a `RequiresViolation`, with the virtual module's packages as
  `providerPackage`.
- `package-info` classes are not checked as module code (they depend on the annotations), primitive types are
  skipped, and arrays are checked by their component type.
- Unknown names in `requires` and duplicate names are checked across modules and virtual modules.

### Known limitation
The processor only sees the `@ExternalModule` declarations compiled in the same round. In an incremental build
(IntelliJ) that recompiles a module's classes but not the `package-info` declaring the virtual modules, it would
report *Unmanaged dependency* for packages that are actually declared. A full build is correct. This has the same
cause as the missing-modules limitation of incremental builds, and the same fix: looking up declarations from
earlier compilations.

## Part 3: Tests

### Processor (`ModuleAnnotationProcessorTest`)
- `usingJavaClasses_producesNoErrors`
- `usingArrayLengthAndClone_producesNoErrors`
- `usingNonJavaClassOutsideAnyModule_producesUnmanagedDependencyError`
- `usingOwnPackageWithoutModule_producesUnmanagedDependencyError`
- `usingClassOfRequiredExternalModule_inSubPackage_producesNoErrors`
- `usingClassOfExternalModuleNotRequired_producesRequiresViolationError`
- `repeatedExternalModules_mostSpecificPackageWins`
- `externalModuleWithSameNameAsModule_producesDuplicateModuleNameError`

### Verifier (`ModularVerifierTest`)
Fixture `externaldeclarations` declares two virtual modules on one package: `annotation` (`javax.annotation`) and
`processing` (`javax.annotation.processing`).
- `testing_module_using_class_of_required_external_module_should_return_success` (`externalrequired`)
- `testing_module_using_class_of_external_module_it_does_not_require_should_return_error`
  (`externalnotrequired`: requires `annotation`, but `processing` is more specific)
- `testing_module_using_class_outside_java_and_any_module_should_return_error` (`unmanaged`)

### Sample
The sample only uses `java.*`. Its README has an experiment with `javax.swing`.

## Migration (breaking change)
Every module that uses a class outside `java.*` and outside the modules must declare that package in an
`@ExternalModule` and require it. The *Unmanaged dependency* error names the package to declare.
