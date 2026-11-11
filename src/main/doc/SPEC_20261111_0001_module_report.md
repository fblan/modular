# SPEC_20261111_0001: Module Report

**Date**: 2026-11-11
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #32](https://github.com/fblan/modular/issues/32) (plan),
[Issue #33](https://github.com/fblan/modular/issues/33) (`ModuleAnalysis`),
[Issue #35](https://github.com/fblan/modular/issues/35) (this report)

---

## Part 1: Feature Description

### Overview
The `report` artifact writes an HTML page on the modules of an application: how big each module is, what really
depends on what, a diagram, and a list of things to look at.

```java
ModularReport.of("com.example").writeTo(Path.of("target/modular-report"));
```

### Purpose
The processor and `ModularVerifier` answer one question: is a rule broken? The report is for the questions around
it, which a person answers by looking:

- Is the shape of the application the one that was intended? Which module is at the bottom, which one is too big?
- Are there cycles between modules? No rule forbids them, so nothing else shows them.
- Which `requires` and `exports` are declared but no longer needed?
- Which classes are in no module?

### Business Rules
1. The report is **one file**, `index.html`. It needs no network access and no other file, and it is complete
   without JavaScript.
2. It follows the browser's light or dark theme, and can be read on a phone.
3. Apart from its generation time, the same classes always give the same page.
4. Names from the code (modules, packages, classes) are escaped everywhere.
5. Test classes are left out by default. `ModularReport.of(ModuleAnalysis)` reports on any analysis.
6. The report never fails a build. Failing on violations stays the job of the processor and of `ModularVerifier`.

### Findings

| Finding                       | Level   | When |
|-------------------------------|---------|------|
| Violation                     | error   | Each violation of `ModuleAnalysis.violations()`. |
| Cycle                         | warning | Modules that use each other, directly or not. One finding per group of modules. |
| Classes outside any module    | warning | Scanned classes that are in no `@Module` and in no `@ExternalModule`. One finding for all of them. |
| `requires` not seen in use    | hint    | A name in `requires` with no dependency on it. Not given for a name that does not exist: that is a violation. |
| Exported package not used     | hint    | An entry of `exports` that matches no package used by another module. |

Hints can be false alarms, and the page says so: the report reads bytecode, so it does not see a constant that the
compiler inlined or an annotation with source retention, and an exported package can be an entry point or be used
by reflection.

The findings are also available in Java, with `ModularReport.findings()`, in the order errors, warnings, hints.

### Sections of the page
1. **Summary**: a verdict in one sentence, and the number of modules, types, dependencies and findings.
2. **Findings**: one block per finding, with the classes and the suggestion behind it.
3. **Diagram**: the modules and the dependencies between them.
4. **Dependency matrix**: the number of uses of each module by each other module. Left out with a single module.
5. **Modules**: an overview table (types, exported types, methods, class file size, uses, used by), then one block
   per module: exports, requires, size, what it uses and who uses it with the classes behind each dependency, and
   its types.
6. **Outside the modules**: the virtual modules, JPMS modules and unmanaged packages that the modules use.

The size of a module is given in types, methods and class file bytes. Lines of code are not available: the report
reads compiled classes, not sources.

### Diagram
- An inline SVG, drawn by the `report` module itself: no JavaScript library, no external tool.
- A module is a box, with its name and its number of types. The box is taller when the module has more types.
- An arrow goes from a module to a module it uses. It is wider when there are more uses.
- A module sits **below** every module that uses it, so arrows point down. A cycle cannot have all its arrows
  pointing down: its arrows are red, and at least one of them points up.
- Virtual modules, JPMS modules and unmanaged packages are not drawn: they are listed in their own section.
- With JavaScript, selecting a module highlights its arrows. Without, the diagram is the same, not interactive.

## Part 2: Implementation

### Module `report`
Depends on `verification` only, and uses its public model, `ModuleAnalysis` (issue #33). Package
`org.asymetrik.modular.report`:

| Class            | Visibility | Role |
|------------------|------------|------|
| `ModularReport`  | public     | Entry point: `of(...)`, `findings()`, `html()`, `writeTo(Path)`, `main` |
| `Finding`        | public     | One finding: level, kind, title, details |
| `ModuleGraph`    | package    | Dependencies between modules, and the cycles (strongly connected components) |
| `Findings`       | package    | Computes the findings from the analysis and the graph |
| `DiagramLayout`  | package    | Positions of the boxes and of the arrows |
| `DiagramSvg`     | package    | The SVG of a layout |
| `ReportPage`     | package    | The HTML page |
| `Text`           | package    | Escaping, plurals, numbers and sizes |

The style sheet and the script are resources (`report.css`, `report.js`), put inline in the page.

### Layout of the diagram
1. **Arrows to turn.** The modules are walked depth-first, starting from those nobody uses, in the order of the
   names. An arrow that reaches a module still being walked closes a cycle: it is laid out reversed, and drawn
   pointing up.
2. **Layers.** The layer of a module is the longest path that leads to it.
3. **Long arrows.** An arrow that passes several layers gets a slot in each layer it crosses, so that it goes
   between the boxes.
4. **Order in a layer.** By the average position of the neighbours, layer by layer, down then up, keeping the
   order with the fewest crossings; then two neighbours are swapped when it removes crossings. When crossings
   remain, the same is done from other starting orders (the opposite order, then shuffles with fixed seeds) and
   the first best result is kept.
5. **Positions.** Each layer is packed and centred, then each box moves towards its neighbours, keeping the order
   and the gaps.
6. **Routes.** An arrow leaves and reaches a box vertically, and curves only between two layers.

Everything is ordered by name and the shuffles are seeded, so the layout is the same on every run.

### Running it
- From a test of the application, which has the right class path:
  `ModularReport.of("com.example").writeTo(Path.of("target/modular-report"))`.
- From the command line, with the classes of the application on the class path:
  `java -cp <class path> org.asymetrik.modular.report.ModularReport <output directory> <package>...`.

`sample-fizzbuzz` has such a test, `ModuleReportTest`, which writes `sample-fizzbuzz/target/modular-report/index.html`.

### Tests
- `ModuleGraphTest`: dependencies between modules, cycles of two and three modules, several cycles, none.
- `FindingsTest`: each kind of finding, their order, the cases without finding.
- `DiagramLayoutTest`: a module is below those that use it, arrows outside a cycle point down, boxes do not
  overlap, arrows do not go through a box, crossings are avoided, the layout is the same on every run.
- `ModularReportTest`: module names, dependency counts, each finding, escaping, the SVG is well formed, no network
  reference, same page for the same classes, `writeTo` and `main`.
- `sample-fizzbuzz`: `ModuleReportTest` checks the three modules of the sample and their dependencies.

## Part 3: Out of Scope
- A section on the requirements (`@Req`, `@TestReq`) of each module.
- A Maven or Gradle plugin that writes the report during the build.
- Failing the build on a finding.
- Lines of code, and anything else that needs the sources.
