# SPEC_20261019_0003: JPMS Module Names in Requires

**Date**: 2026-10-19
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #20](https://github.com/fblan/modular/issues/20), SPEC_20261019_0002_external_modules.md

---

## Part 1: Feature Description

### Overview
A dependency that belongs to a JPMS module can be required by that module's name, with no `@ExternalModule`
declaration. This covers JDK modules (`java.desktop`, `java.sql`, `jdk.compiler`, ...), modules on the module
path, and jars on the class path, which get the name JPMS would give them.

```java
@Module(name = "app", exports = {}, requires = {"engine", "java.desktop", "jakarta.validation"})
package com.example.app;
```

### Purpose
- Avoid declaring an `@ExternalModule` for every library that already has a module name.
- Use the names developers already know from `module-info.java`.

### Business Rules

1. `java.*` packages stay free, whatever their JPMS module.
2. The owner of any other dependency is resolved in this order:
   1. the `@Module` whose package contains it;
   2. the most specific `@ExternalModule` covering its package;
   3. its JPMS module name:
      - its named module, when it is in one (JDK, module path);
      - otherwise, for a class loaded from the class path, the module name of its jar or directory, as
        `java.lang.module.ModuleFinder` gives it: the `module-info`, else the `Automatic-Module-Name` manifest
        entry, else the name derived from the jar's file name.
3. The consumer module must have the owner's name in `requires`. Otherwise it is a *Requires violation* that
   names it.
4. A dependency with no owner (for example a package of the current sources without `@Module`, which is in no jar)
   is an *Unmanaged dependency*.
5. Explicit declarations win: when an `@ExternalModule` covers a package, its name must be required, not the JPMS
   name.

## Part 2: Implementation

### `ModuleAnnotationProcessor`
Uses public APIs only:
- `Elements.getModuleOf(type)`: named module for JDK and module path classes.
- For a class in the unnamed module: `Filer.getResource(StandardLocation.CLASS_PATH, package, "Top.class")` gives
  the URI of its class file (`jar:file:...!/...` or `file:...`), from which the jar or directory is found and
  passed to `ModuleFinder.of(path)`.
- Results are cached per package. Any failure (resource not found, invalid automatic name) means "no JPMS name".

### `ModularVerifier`
- `Class.forName(name, false, loader)`: named module from `Class.getModule()`, else `ModuleFinder.of(...)` on the
  class's code source location. Cached per package.
- Unknown names in `requires`: a name is known if it is a module, a virtual module, a JDK module
  (`ModuleFinder.ofSystem()`), or the JPMS module of a dependency of the scanned classes. A library that is required
  but not used by any scanned class is therefore reported as unknown.

## Part 3: Tests

### Processor (`ModuleAnnotationProcessorTest`)
`usingNonJavaClassOutsideAnyModule_producesUnmanagedDependencyError` used `javax.annotation.processing`, which now
resolves to `java.compiler`. It is replaced by:
- `usingJdkClassOfJpmsModuleNotRequired_producesRequiresViolationError`
- `usingJdkClassOfRequiredJpmsModule_producesNoErrors`
- `usingClassPathJarClassOfJpmsModuleNotRequired_producesRequiresViolationError` (junit-jupiter-api,
  `org.junit.jupiter.api`)
- `usingClassPathJarClassOfRequiredJpmsModule_producesNoErrors`

`usingOwnPackageWithoutModule_producesUnmanagedDependencyError` still covers unmanaged dependencies.

### Verifier (`ModularVerifierTest`)
- The `unmanaged` fixture now uses `nomodules.RegularClass` (no JPMS name) instead of a JDK class.
- `testing_module_using_classes_of_required_jpms_modules_should_return_success` (`jpmsrequired`: `java.compiler`
  and `org.junit.jupiter.api`)
- `testing_module_using_class_of_jpms_module_it_does_not_require_should_return_error` (`jpmsnotrequired`)

### Sample
Tried by hand with `jakarta.validation-api` 3.1.1: without the requires, the error names `jakarta.validation`, and
with it, the build passes. The README's `javax.swing` experiment now asks for `java.desktop`.
