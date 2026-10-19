# SPEC_20261019_0005: Linking Tests to Requirements

**Date**: 2026-10-19
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #25](https://github.com/fblan/modular/issues/25), SPEC_20261019_0004_module_requirements.md

---

## Part 1: Feature Description

### Overview
`@TestReq` links a unit test to the requirements (`@Req` with an `id`) it checks. When the tests compile, the
requirement processor reports ids that match no requirement, and warns about requirements that no test covers.

```java
class FizzBuzzTest {

    @Test
    @TestReq("ENG-1")
    void saysTheNumberWhenNoRuleMatches() { ... }

    @Test
    @TestReq({"ENG-2", "RUL-1", "RUL-2"})
    void concatenatesTheWordsInRuleOrder() { ... }
}
```

### Purpose
- Traceability: from a requirement to the tests that check it, and back.
- Catch typos and references to removed requirements at compile time.
- Show untested requirements on every build.

### Business Rules
1. `@TestReq(String[] value)` goes on a test method, or on a test class, meaning all its tests.
2. Each id must be the id of a `@Req`; otherwise it is a compile **error**, reported on the test.
3. Each `@Req` with an id that no `@TestReq` references gives a compile **warning**, naming the requirement and the
   `@TestReq` to add.
4. Opt-in: these checks only run in a compilation that contains at least one `@TestReq`. Requirements without an
   id cannot be referenced and are not reported.

## Part 2: Implementation

### Requirement index
`@Req`s are compiled with the main code, so they are not part of the test compilation. `RequirementProcessor`
therefore writes an index when it compiles `@Req`s with ids:

- File: `META-INF/modular/requirements.properties` in the class output (so in `target/classes`, and in the jar).
- One sorted line per requirement, `<id>=<module>|<sentence>`, without the date comment `Properties` would add, so
  that it only changes with the requirements.
- Incremental builds: the existing index is read first, and the entries of the modules not recompiled are kept.

When the tests compile, the processor reads the index from the class path (where Maven puts `target/classes`) with
`Filer.getResource(StandardLocation.CLASS_PATH, ...)`, and adds the requirements of the current compilation.

### Checks
- Unknown ids are checked in the round where the `@TestReq` is found: errors reported when processing is over lose
  their source position.
- Coverage is reported when processing is over, once all tests are known. Warnings have no source position.
- If no requirement with an id is known at all, a single warning says the ids cannot be checked, and suggests
  enabling the processor for the main code.

### `@TestReq`
`@Target({METHOD, TYPE})`, `@Retention(RUNTIME)` so that test reports and extensions can read it.

### Known limitations
- Coverage is only meaningful when all tests are compiled together. An incremental build that recompiles a single
  test class warns about the requirements the other tests cover.
- Only the first index on the class path is read: tests can reference the requirements of their own Maven module,
  not those of another Maven module.
- Test classes in a module's package are not checked by `ModuleAnnotationProcessor` today, because their module's
  `package-info` is not part of the test compilation. If the processor learns to find modules compiled earlier, it
  will need a rule for test code (which uses JUnit, AssertJ, ...).

## Part 3: Tests (`RequirementProcessorTest`)
Compilations now write to a temporary directory (`-d`); a second compilation, with that directory on the class
path, plays the test compilation.
- `requirementsWithId_areWrittenToTheIndex`
- `recompilingSomeModules_keepsTheIndexEntriesOfTheOthers`
- `testReqWithIdsFromTheIndexOnTheClassPath_producesNoErrors`
- `testReqOnClass_coversTheRequirement`
- `testReqWithUnknownId_producesUnknownIdError`
- `requirementWithoutTest_producesCoverageWarning`
- `testReqAndRequirementsInTheSameCompilation_areChecked`
- `testReqWithoutAnyKnownRequirement_producesWarning`

### Sample
`sample-fizzbuzz` now has unit tests (JUnit, AssertJ) for RUL-1, RUL-2, ENG-1, ENG-2, ENG-3 and APP-2. APP-1 is left
without a test on purpose, so the build shows the coverage warning. Checked by hand: an unknown id fails the test
compilation, with the error on the test's line.
