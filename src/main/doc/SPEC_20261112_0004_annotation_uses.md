# SPEC_20261112_0004: Annotation Uses

**Date**: 2026-11-12
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #44](https://github.com/fblan/modular/issues/44), SPEC_20261112_0002_design_hints.md

---

## Part 1: Feature Description

### Overview
`ModuleAnalysis` tells which uses are only annotations, and the report's leaky-API hint leaves them out.

### Purpose
The leaky-API hint counted every use of B by A's exported types, annotations included. An annotation on an exported
type is nothing its users call: they can use the type without requiring the annotation's module. On a real
application (simpleprojects), 4 of its 5 leaky-API hints were such false alarms: JPA's `@Embeddable` on an id,
Jackson's annotations on a view (with `OptBoolean` as an annotation value), and `@InterceptorBinding` on an
interceptor annotation. The fifth, a real one, stays: `task`'s exported events are `DomainEvent`s of `events`, which `project` uses without requiring `events`.

### Business Rules
1. A use is **only in annotations** when every dependency of the consumer, and of its nested classes, on the used
   class comes from an annotation: the annotation's type, on the class, a field, a method, a constructor or a
   parameter, or a value of the annotation, such as an enum constant or a nested annotation.
2. A use that is also anywhere else (a signature, a body, a nested class's field) is not only in annotations.
3. The leaky-API hint ignores the uses of B by A's exported types that are only in annotations. When nothing else of
   A's exported types uses B, there is no hint.
4. Nothing else changes: these uses are still dependencies, still need a `requires`, and still show in the report.

## Part 2: Implementation
- `ModuleAnalysis.ClassUse` gets `annotationsOnly`. The constructors without it stay, and mean `false`. When the
  uses of a type and of its nested classes are merged, it is only in annotations when all of them are.
- ArchUnit says which dependency comes from an annotation only in its description: "... is annotated with <type>"
  or "... has annotation member of type <type>" (`Dependency.convertTo(JavaAnnotation.class)` is empty for them).
  `ModuleAnalysis.fromAnnotation` reads it; the tests check it on each kind of annotation, so that an ArchUnit
  upgrade that changes these words fails a test.
- `Findings.findLeakyApis` filters the exposing uses on `annotationsOnly()`.

## Part 3: Tests
- `ModuleAnalysisTest`, on the `annotated` fixture: an annotation on a type, on a field, a method and a parameter,
  an enum as an annotation's value, a type in an annotation and in a signature, and in an annotation of a type and
  a field of its nested class.
- `FindingsTest`, on `designapp`: `CoreException`, exported by `core`, is `@Audited` of a `marks` module, which
  `client` uses without requiring `marks`: no hint for `marks`. Without the filter, the test fails.

## Part 4: Out of Scope
- Leaving out other uses that a type's users never see, such as a use only inside a method body.
