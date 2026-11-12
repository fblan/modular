# SPEC_20261112_0002: Design Hints

**Date**: 2026-11-12
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #40](https://github.com/fblan/modular/issues/40), SPEC_20261111_0001_module_report.md,
SPEC_20261112_0001_exclusions_and_jdk_modules.md

---

## Part 1: Feature Description

### Overview
The module report gives four more hints, about the design rather than the rules: large types, leaky APIs, borrowed
exceptions and exported helpers. `ModuleAnalysis` gives the data behind them.

### Purpose
The report flagged broken rules, cycles, classes in no module, and `requires` or `exports` that seem unused. On a real
application (10 modules, no finding), its numbers still showed problems that no finding pointed at, and that only a
careful read found: one class held almost half of its module, a module's API handed on another module's types, a
module threw another module's exceptions, and a module exported a class of static helpers. Each one is a common
design problem that bytecode shows, so the report can point at it in any application.

### Business Rules
1. **Large type**: a top-level type with more than 35 methods (SonarQube's default for too many methods). Methods
   are counted as in `TypeDescription.methods()`: nested classes count with their top-level type, and the ones the
   compiler adds (lambdas, bridges) don't count. One hint per type. Its details give its class file size and its
   share of its module, when known, and a suggestion.
2. **Leaky API**: for a module A, a target B and a module C, when A's exported types use B, C uses those exported
   types, and C doesn't require B. C can't use what it receives without a new `requires`. B is a module, a virtual
   module or a JPMS module other than `java.*` (which is free); a use of an unmanaged package is already a violation.
   C is not B itself. One hint per (A, B), naming every such C. Its details give the uses of B by A's exported types,
   C's uses of them, and a suggestion.
3. **Borrowed exception**: a module A creates instances (calls a constructor) of an exception type of another module
   B. A's errors then depend on how B handles them, for example the HTTP status B's error mappers give them. Catching
   or declaring B's exception is not a hint, and neither is extending it: a constructor's call to `super(...)` creates
   no instance of the superclass. One hint per (A, B). Its details give the classes and the exceptions, and a
   suggestion.
4. **Exported helper**: an exported class of static helpers, which another module uses. It is shared code that
   belongs to neither module. A class of static helpers is not an interface, enum, record or annotation, has at least
   one method, every method and field is static, and every constructor is private. A class of constants only (no
   method) is not one. Neither is a static factory, such as a module's entry point: a class with a method that
   returns a type of its own module, or an array of one. One hint per class. Its details give the classes that use it, and a suggestion.
5. All four are hints: the report says that a hint can be a false alarm, and that a large type, an exported helper
   or a shared exception can be a choice. The limit of 35 methods is fixed.
6. The hints come after the other hints, in the order above.

## Part 2: Implementation

### `verification`
- `TypeDescription` gets `exception` (assignable to `Throwable`) and `staticHelper` (rule 4), read on the top-level
  class. The constructor without them stays, and means `false` for both.
- `ClassUse` gets `createsInstances`: the consumer, or a nested class of it, calls a constructor of the used class,
  other than a constructor's call to its superclass's constructor. The constructor without it stays, and means
  `false`. A use is kept once per (consumer, used class): it creates instances when one of its classes does.

### `report`
- `Finding.Kind` gets `LARGE_TYPE`, `LEAKY_API`, `BORROWED_EXCEPTION` and `EXPORTED_HELPER`.
- `Findings.MAX_METHODS` is 35. `Findings` finds the four hints from `ModuleAnalysis`'s modules and dependencies.
- `ReportPage`'s note on hints says that these can be choices.

### Tests
- `ModuleAnalysisTest`, on the `traits` fixture: which types are exceptions or static helpers (a helper, a factory,
  constants only, a public constructor, an instance method, an interface, an enum, a record), which uses create instances (a
  `throw new`, from a nested class too, but not `super(...)` nor a `catch`), and the shorter constructors.
- The sample's `Rules`, a static factory of `Rule`s, is not an exported helper: the sample's report stays empty.
- `FindingsTest`, on the `designapp` fixture: one of each hint, a type of exactly 35 methods that is not large, and a
  module that requires the leaked module or only catches the exception. Their details. `testapp` gets three leaky
  APIs, which are real ones.

## Part 3: Out of Scope
- Setting the limit of methods, or turning a hint off: fixed for now, an option can come when someone needs it.
- Other measures of a large type (lines, complexity, fields): the analysis reads bytecode, and methods are what
  SonarQube counts by default.
- Telling which part of an exported type leaks: any use of B by an exported type counts, even in a method body.
