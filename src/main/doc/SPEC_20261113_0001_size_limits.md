# SPEC_20261113_0001: Size Limits

**Date**: 2026-11-13
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #46](https://github.com/fblan/modular/issues/46), SPEC_20261112_0002_design_hints.md,
SPEC_20261112_0003_module_colors_and_large_modules.md

---

## Part 1: Feature Description

### Overview
The report measures sizes in units developers think in: a module in types, a type in public methods, a method in
lines of code. Class file bytes are gone from the report.

### Purpose
The large-module warning said "'billing' has 48.2 kB of class files, 3.8 times the median module's": bytes are not
a unit anyone reasons in, and a module compared to the median is large or not depending on the others. A type was
large above 35 methods, private helpers included, though what makes a type hard to use is its public surface. And a
long method, the most common size problem, was not reported.

### Business Rules
1. **Large module** (warning): a module with more than 50 types (top-level; nested, local and anonymous classes count
   with their top-level type). The limit is the same whatever the other modules. Its details give its public methods
   and lines of code, and a suggestion.
2. **Large type** (hint): a type with more than 20 public methods. Constructors and the methods the compiler adds
   are not counted, nor the methods of its nested classes. Its details give a suggestion.
3. **Large method** (hint): a method, constructor or static initializer with more than 75 lines of code (SonarQube's
   default, rule S138). One hint per method, after the large types. Its details give a suggestion.
4. **Lines of code** are the source lines that hold code, read from the line numbers of the class files (javac
   writes them unless compiled with `-g:none`). Comments, blank lines and declarations without code are not counted;
   the closing brace of a method that returns nothing is, as it holds the return. The body of a lambda is counted
   with the method that holds it. A type's lines are those of all its class files, each line once. When a class
   file cannot be read or has no line numbers, the type's lines are unknown and its methods are not checked.
5. **Sizes in the report**: the summary, the modules table, each module's section and its types table give types,
   public methods and lines of code instead of methods and class file bytes. When the lines of one type of a module
   are unknown, the module's lines are unknown ("unknown" in a table, left out in a sentence).
6. The limits are fixed.

## Part 2: Implementation
- `ModuleAnalysis.TypeDescription` gets `publicMethods`, `linesOfCode` (`OptionalInt`) and `methodsWithCode`
  (`MethodDescription`: name such as `Order.Line.<init>(String)`, and lines of code). The constructors without them
  stay, and mean 0, unknown and none. `methods` and `classFileBytes` stay.
- `ClassFileLines` reads the `LineNumberTable`s of a class file, without a new dependency (ArchUnit's ASM is shaded
  and gives no line table). A lambda `lambda$compute$0` goes to the method named `compute` (`new`: a constructor,
  `static`: the static initializer); among overloads, to the one whose lines surround the lambda's.
- `Findings.MAX_TYPES` is 50, `MAX_PUBLIC_METHODS` 20, `MAX_LINES` 75; `Finding.Kind` gets `LARGE_METHOD`.
  `LARGE_MODULE_FACTOR`, `LARGE_MODULE_MIN_MODULES`, `LARGE_MODULE_MIN_TYPES` and `MAX_METHODS` are gone.

## Part 3: Tests
- `ClassFileLinesTest`: a class compiled with `-g`, with a lambda in one of two overloads; the same with `-g:none`;
  a class without code; bytes that are not a class file; parameter types.
- `ModuleAnalysisTest`: the public methods, lines of code and methods with code of `ProductIndex` and its nested
  class.
- `FindingsTest`: `sizeapp`'s `large` (51 types) is a large module, `limit` (50) is not; `designapp`'s `Big`
  (21 public methods) is a large type, `Medium` (20 public, 15 not) is not; `Lengthy.tooLong` (76 lines) is a large
  method, `atTheLimit` (75) is not.
- `ModularReportTest`: the columns of the modules table, the size of a module, the row of a type.

## Part 4: Out of Scope
- Setting the limits.
- Counting lines from the sources, or the lines of declarations.
