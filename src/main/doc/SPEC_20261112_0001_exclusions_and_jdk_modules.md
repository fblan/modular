# SPEC_20261112_0001: Generated Code and JDK Modules

**Date**: 2026-11-12
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #38](https://github.com/fblan/modular/issues/38), SPEC_20261111_0001_module_report.md,
SPEC_20261019_0003_jpms_module_requires.md

---

## Part 1: Feature Description

### Overview
`ModuleAnalysis`, and so the module report, can leave out the code that a framework generates or weaves into an
application. A use of a JDK module other than `java.base` becomes a dependency.

```java
ModuleAnalysis.of(ClassScope.WITHOUT_TESTS, List.of("com.example"),
	new Exclusions(List.of("**$$*", "**RepositoryImpl"), List.of("io.example.framework.Link")));
```

```bash
java -cp <class path> org.asymetrik.modular.report.ModularReport \
	'--exclude-class=**$$*' '--exclude-use=io.example.framework.Link' target/modular-report com.example
```

### Purpose
Frameworks that work at build time put code in the application's packages:

- **Generated classes**, such as JSON bindings, REST adapters and repository implementations. They use the
  framework's modules, which no `requires` lists.
- **Woven code** in hand-written classes, such as a constructor parameter of a framework type.

The processor reads sources, so it never sees this code. `ModuleAnalysis` reads bytecode, so it does. On a vidocq
application with 10 modules, it gave 865 violations, none of them in the application's own code. Leaving out the
generated classes and one woven type left no violation.

A use of `java.*` breaks no rule, but `ModuleAnalysis` used to leave such uses out of the dependencies
altogether. The report then gave the hint "requires 'java.net.http', but no use of it was found" for a module that
does use it.

### Business Rules
1. A **pattern** matches a fully qualified class name, with nested classes written with `$`. `*` matches any
   characters except `.`, `**` matches any characters, and every other character is literal. The whole name must
   match. A blank pattern is rejected.
2. An **excluded class** is left out of the analysis as if it were not on the class path: it is not measured, its
   uses are not analysed, and it is not a class in no module. A nested class is excluded when it or its top-level
   class matches.
3. An **excluded use** is ignored: a use of a class that matches is no dependency and no violation.
4. Without exclusions, the analysis is the same as before: `Exclusions.NONE`.
5. A use of a `java.*` class outside `java.base` is a dependency on its JDK module, of kind `JPMS_MODULE`. It
   breaks no rule, since `java.*` can be used without `requires`, as in the processor.
6. Uses of `java.base` stay left out: every class uses it.
7. The report never gives the hint "requires not seen in use" for `java.base`.
8. When the analysis has exclusions, the report says which patterns were left out, under the scanned packages.

## Part 2: Implementation

### `verification`
- `ModuleAnalysis.Exclusions(List<String> classes, List<String> uses)`: a public record, with `NONE` and
  `isEmpty()`. Its compact constructor rejects blank patterns and copies the lists. `toRegex` turns a pattern into
  a regular expression: `**` gives `.*`, `*` gives `[^.]*`, and the rest is quoted.
- `ModuleAnalysis.of(ClassScope, List<String>, Exclusions)`: the new factory. The other factories pass
  `Exclusions.NONE`. `exclusions()` returns them.
- Excluded classes are filtered out right after the import, before the modules are found, so they can't count
  anywhere. Excluded uses are filtered out of each class's dependencies, and of the JPMS modules collected for the
  check of `requires` names.
- A `java.*` dependency goes through `findJpmsModuleName`, which gives its JDK module. `java.base` is dropped,
  and any other module is recorded as a `JPMS_MODULE` target, with no violation.

### `report`
- `ModularReport.main` reads `--exclude-class=<pattern>` and `--exclude-use=<pattern>`, repeated as needed, before
  or between the output directory and the packages. An unknown option, a blank pattern or a missing operand prints
  the usage and exits with status 2.
- `ReportPage` adds "Left out: classes matching …; uses of …." under the scanned packages.
- `Findings` skips `java.base` in "requires not seen in use".

### Tests
- `ModuleAnalysisTest`: the `exclusions` fixture without exclusions (four violations, one generated class outside
  the modules), with them (no violation, no dependency, the nested class gone with its top-level class), with
  uses only. Also the patterns, blank patterns, and the `jdkuse` fixture (a `java.net.http` dependency, no
  `java.base`, no violation).
- `FindingsTest`: the `jdkapp` fixture (only the unused `java.management` gets a hint), and excluded classes giving
  no finding.
- `ModularReportTest`: the command-line options and the line of the page, for both kinds of exclusion and
  without exclusions.

## Part 3: Out of Scope
- Recognising generated code by itself: `@Generated` has source retention, so bytecode doesn't carry it, and
  frameworks name their classes in their own ways.
- Exclusions in `ModularVerifier`: build a `ModuleAnalysis` with them and assert on its `violations()`.
