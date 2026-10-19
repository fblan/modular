# SPEC_20261019_0004: Module Requirements in EARS Format

**Date**: 2026-10-19
**Status**: Implemented
**Author**: Project Team
**Version**: 1.0
**Related**: [Issue #23](https://github.com/fblan/modular/issues/23)

---

## Part 1: Feature Description

### Overview
A new Maven module, `specification`, lets each module state its requirements next to its `@Module` declaration.
A requirement is one sentence in EARS format (Easy Approach to Requirements Syntax), written in a repeatable
`@Req` annotation and checked at compile time by an annotation processor shipped in the same jar.

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
@Req("While the upper bound is below 1, the engine shall say nothing.")
package com.example.engine;
```

### Purpose
- Keep what a module must do next to its boundaries, in the code, where it is reviewed and versioned with it.
- Use a light, widely known syntax (EARS) that reads like a requirements document.
- Catch badly written requirements at compile time.

### Design choice: one sentence rather than structured fields
Structured attributes (`when = ..., shall = ...`) were considered and rejected. They split each requirement into
pieces that are harder to read, need awkward names for the Java keywords `while` and `if`, and become heavy for
combined clauses. A single sentence reads like the requirement it is, and its structure can still be checked.

### Business Rules

1. **Placement**: `@Req` is only allowed on a package that has `@Module`. Requirements describe a module.
2. **EARS sentence**: the requirement starts with an uppercase letter and follows one of the patterns:
   - Ubiquitous: *The &lt;module&gt; shall &lt;response&gt;*
   - Event-driven: *When &lt;trigger&gt;, the &lt;module&gt; shall &lt;response&gt;*
   - State-driven: *While &lt;state&gt;, the &lt;module&gt; shall &lt;response&gt;*
   - Unwanted behaviour: *If &lt;condition&gt;, then the &lt;module&gt; shall &lt;response&gt;*
   - Optional feature: *Where &lt;feature is included&gt;, the &lt;module&gt; shall &lt;response&gt;*
   - Combined: any sequence of `Where`, `While` and `When` clauses, then optionally an `If …, then` clause, e.g.
     *While &lt;state&gt;, when &lt;trigger&gt;, the &lt;module&gt; shall &lt;response&gt;*.

   Keywords are matched ignoring case, and a final period is optional.
3. **Subject**: the system in the sentence is the module, as named in `@Module(name = ...)`, ignoring case.
4. **Ids**: optional; when set, unique.

## Part 2: Implementation

### Maven module `specification`
- `org.asymetrik.modular.specification.Req`: `String value()`, `String id() default ""`, `@Target(PACKAGE)`,
  `@Repeatable(Reqs.class)`.
- `org.asymetrik.modular.specification.Reqs`: the container.
- `org.asymetrik.modular.specification.processor.RequirementProcessor`, registered in
  `META-INF/services/javax.annotation.processing.Processor`. The module compiles with `<proc>none</proc>`, like
  `processor`, so it does not try to run its own processor.
- No dependency on `annotations`: the processor reads `@Module` and its `name` by qualified name through
  `AnnotationMirror`s. `annotations` is only a test dependency.
- Published to Maven Central like `annotations` and `processor`.

### Usage
```xml
<dependency>
    <groupId>org.asymetrik.modular</groupId>
    <artifactId>specification</artifactId>
    <version>${modular.version}</version>
    <scope>provided</scope>
</dependency>
...
<annotationProcessorPaths>
    <path>
        <groupId>org.asymetrik.modular</groupId>
        <artifactId>specification</artifactId>
        <version>${modular.version}</version>
    </path>
</annotationProcessorPaths>
```

### Checks
The sentence check is a regular expression:
`(?:(?:Where|While|When) .+?, )*(?:If .+?, then )?the (.+?) shall (\S.*)` (ignoring case), plus an uppercase first
letter. Group 1 is compared to the module name. Errors are reported on the package, and quote the requirement.
When a sentence is not EARS, the error lists the patterns with the module's name filled in.

### Known limitation
Ids are compared within one compilation. An incremental build that recompiles only some `package-info` files does
not see the ids of the others.

## Part 3: Tests (`RequirementProcessorTest`)
- Each EARS pattern, combined clauses, and a clause containing commas: no errors.
- Not EARS (`must` instead of `shall`, misspelled keyword, `If` without `then`, lowercase start, no `the`, no
  response, blank): one EARS error listing the patterns.
- Another subject (`the system`): subject error. Subject compared ignoring case.
- `@Req` on a package without `@Module`: error.
- Only the invalid requirement among several is reported.
- Duplicate ids across modules: error. Several requirements without id: no error.

### Sample
`sample-fizzbuzz` declares requirements on `rules`, `engine` and `app`, using all five patterns, and enables the
processor. Its README has an experiment to break a requirement.
