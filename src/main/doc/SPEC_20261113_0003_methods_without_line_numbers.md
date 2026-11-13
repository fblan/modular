# SPEC_20261113_0003: Methods Without Line Numbers

**Date**: 2026-11-13
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #49](https://github.com/fblan/modular/issues/49), SPEC_20261113_0001_size_limits.md

---

## Part 1: Feature Description

### Overview
A method that has code but no line numbers is left out of the lines of code, instead of making its whole type
unknown.

### Purpose
Some frameworks add methods to the classes they build: Vidocq's CDI container adds a constructor
(`protected IdGenerator(io.vidocq.vauban.api.ProxyLink)`) to every bean class, with code but no line numbers. In
0.1.4, one such method made the lines of its type unknown, and so those of its module. On an application where every
module holds a bean (listit), every module was unknown. Excluding the proxy classes did not help, as the constructor
is added to the bean class itself.

### Business Rules
1. A method with code but no line numbers is left out: its lines are not counted, and it is not checked against the
   method limit. The other methods of the class are counted.
2. A class file is unknown only when none of its methods with code has line numbers: it was compiled with
   `-g:none`. A class file without code (an interface) has no lines, as before.

## Part 2: Implementation
- `ClassFileLines.read` gives up only when it saw methods without line numbers and none with.

## Part 3: Tests
- `ClassFileLinesTest`: a class compiled with `-g` whose method `twice(String)` loses its line numbers (with the ASM
  that ArchUnit shades) keeps the lines of its other methods; the same class compiled with `-g:none` is unknown.

## Part 4: Out of Scope
- Telling the user which methods were left out.
