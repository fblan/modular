# SPEC_20261113_0002: Module Order and Filter

**Date**: 2026-11-13
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #48](https://github.com/fblan/modular/issues/48), SPEC_20261113_0001_size_limits.md,
SPEC_20261112_0003_module_colors_and_large_modules.md

---

## Part 1: Feature Description

### Overview
The Modules section of the report lists the modules to look at first, and can be filtered by name.

### Purpose
The modules were listed in the order of their packages: on an application with many modules, the ones with findings
were scattered, and finding one module meant scrolling.

### Business Rules
1. **Order**: the modules table and the module sections list the modules by their most serious finding (errors,
   then warnings, then hints, then none), then by lines of code, the largest first (a module whose lines are unknown
   after the others), then by types, the most first, then by name. A note under the heading says so.
2. The ids of the sections (`module-N`) stay those of the analysis order, so that links do not change.
3. **Filter**: with more than one module, a search box above the table keeps the rows and the sections whose module
   name contains its text, ignoring case, and says how many modules match ("2 of 15 modules", "No module matches").
   An empty box shows every module. The diagram, the matrix and the findings stay complete.
4. Without JavaScript, the box is hidden and every module is shown.

## Part 2: Implementation
- `ReportPage.moduleOrder()` sorts the module indexes. Rows and sections get `data-name`.
- `report.js` filters rows and sections on `data-name`; `report.css` styles `.filter`.

## Part 3: Tests
- `ModularReportTest`: the order of `testapp`'s rows and sections (`legacy`, `billing`, `orders`, `catalog`, `web`),
  their ids, the filter box, and no box for a single module.

## Part 4: Out of Scope
- Sorting by another column, or filtering the diagram and the matrix.
