# SPEC_20261112_0003: Module Colors and Large Modules

**Date**: 2026-11-12
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #42](https://github.com/fblan/modular/issues/42), SPEC_20261111_0001_module_report.md,
SPEC_20261112_0002_design_hints.md

---

## Part 1: Feature Description

### Overview
The module report warns about modules much larger than the others, and colors each module that has findings with the
level of its most serious one, so that the modules to look at stand out.

### Purpose
The findings were listed apart from the modules: to see which modules had problems, the reader had to read every
finding. And a module much larger than the others, a common sign that it holds several parts of the domain, was not
reported. On a real application (10 modules), two modules held half of the class files: one was 3.8 times the median
module, the other 3.2 times.

### Business Rules
1. **Large module** (warning): a module with at least 20 types whose class files are more than 3 times the median
   module's. Only when there are at least 3 modules: with fewer, the median says nothing. A module with fewer than 20
   types is small whatever the others, which keeps small applications quiet. The median of an even number of modules
   is the mean of the two in the middle. No module is large when no class file could be read. One warning per module.
   Its details give its types and methods, its 3 largest types with their share of the module, and a suggestion.
   The limits are fixed.
2. The large-module warnings come after the other warnings (cycles, classes in no module).
3. **Modules of a finding**: each finding names the modules to look at:
   - a violation: the module whose classes break the rule (the nested module, for a nested module);
   - a cycle: its modules;
   - classes in no module: none;
   - a large module, an unused `requires` or `exports`, a large type, an exported helper: the module;
   - a leaky API: the module whose exported types leak, then the modules that receive them;
   - a borrowed exception: the module that creates the exceptions.
4. **Colors**: a module with findings takes the color of the most serious one (red for an error, amber for a warning,
   blue for a hint), the page's colors for these levels:
   - in the diagram, the box's border and background, with its findings in a few words ("1 warning, 2 hints") in its
     label and tooltip; the caption explains the colors;
   - in the modules table, a colored edge on the row, and a mark with the findings in a few words;
   - in the module's section, a colored edge and a list of its findings, each linked to the Findings section.
   A module without findings, and a page without findings, look as before.

## Part 2: Implementation
- `Finding` gets `modules`. The constructor without it stays, and means no module.
- `Finding.Kind` gets `LARGE_MODULE`. `Findings.LARGE_MODULE_FACTOR` is 3, `LARGE_MODULE_MIN_MODULES` 3,
  `LARGE_MODULE_MIN_TYPES` 20. `Findings.about(module)` gives a module's findings, `levelOfModules()` the most
  serious level of each module that has some.
- `ReportPage` gives each finding an id (`finding-N`), and `DiagramSvg` a class per level on the boxes.

## Part 3: Tests
- `FindingsTest`: the `sizeapp` fixture (a module of 20 types beside three of one type) has one large module, with
  its details; `testapp`'s `catalog`, 4.4 times the median but with 3 types, is not one; the modules of each finding
  of `testapp`, and the level of each module.
- `ModularReportTest`: the classes of the sections, rows and boxes, the marks, the list of findings in a section and
  its links; a page without findings has none.

## Part 4: Out of Scope
- Setting the limits, or measuring a module in types or methods instead of class file bytes.
- Coloring the dependency matrix.
