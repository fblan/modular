---
name: modular
description: Use modular (org.asymetrik.modular), which turns Java packages into modules checked at compile time with @Module, exports, requires and @ExternalModule, plus EARS requirements with @Req and @TestReq. Use when the user wants to modularize a Java code base, add or change a @Module, set up modular in Maven or Gradle, understand or fix a modular error (Requires violation, Export violation, Unmanaged dependency, Module hierarchy violation, Duplicate module name, EARS/requirement errors, Unknown requirement id), write @Req requirements, or link tests with @TestReq.
---

# modular

modular declares modules on Java packages with annotations in `package-info.java`. Two annotation processors check
the rules when the code compiles. Group: `org.asymetrik.modular`. Current version: `0.1.0`. Java 21+.
Project: https://github.com/fblan/modular

## The model, in brief

```java
@Module(name = "billing", exports = {".", "com.example.billing.api.*"}, requires = {"customers", "java.sql"})
@Req(id = "BIL-1", value = "When an order is paid, the billing shall issue one invoice.")
package com.example.billing;

import org.asymetrik.modular.annotations.Module;
import org.asymetrik.modular.specification.Req;
```

- A module is a `@Module` package plus all its sub-packages. Modules may not be nested.
- `name`: unique across modules and virtual modules.
- `exports`: `"."` is the module's own package only. A full package name exports exactly that package.
  `"a.b.*"` exports the sub-packages of `a.b`, not `a.b` itself. `{}` exports nothing.
- `requires` (default `{}`): the names of the modules this module may use. Not transitive.
- Outside the modules, only `java` and `java.*` are free. Every other dependency needs an owner that is required:
  1. a `@Module` (its exported packages only);
  2. else the most specific `@ExternalModule` covering the package (everything exported);
  3. else its JPMS module name: JDK modules (`java.desktop`, `java.sql`, `java.compiler`, `jdk.compiler`, ...), or
     for a class path jar its `module-info` name, else `Automatic-Module-Name`, else the name derived from the file.
- `@ExternalModule(name = "slf4j", packages = {"org.slf4j"})`, repeatable on any `package-info.java`, declares a
  virtual module. A listed package covers its sub-packages.

## Setting up a project

Maven: add the annotations, and the processors in `annotationProcessorPaths`. That part is required on JDK 23+,
where javac no longer discovers processors on the class path.

```xml
<dependency>
    <groupId>org.asymetrik.modular</groupId>
    <artifactId>annotations</artifactId>
    <version>0.1.0</version>
    <scope>provided</scope>
</dependency>
<dependency> <!-- only if the user wants @Req / @TestReq -->
    <groupId>org.asymetrik.modular</groupId>
    <artifactId>specification</artifactId>
    <version>0.1.0</version>
    <scope>provided</scope>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.asymetrik.modular</groupId>
                <artifactId>processor</artifactId>
                <version>0.1.0</version>
            </path>
            <path> <!-- only with specification -->
                <groupId>org.asymetrik.modular</groupId>
                <artifactId>specification</artifactId>
                <version>0.1.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

If `annotationProcessorPaths` already lists other processors (Lombok, MapStruct, ...), add these paths next to
them. Don't replace them.

Gradle: `compileOnly` for `annotations` (and `specification`), `annotationProcessor` for `processor` (and
`specification`). For `@TestReq`, also add `testCompileOnly` and `testAnnotationProcessor` for `specification`.

Optional test-time check, in the `verification` artifact (test scope):
`new ModularVerifier("com.example").verify()` returns `ModularVerifier.Valid` or the first violation. Each
violation has `formatError()`. It also reports names in `requires` that match nothing, which the processor does not.

## Modularizing an existing code base

Work from the code, not from guesses, and keep the user in the loop on the module boundaries.

1. **Find the candidate modules.** List the packages under the base package (for example with
   `find src/main/java -type d`). Candidates are the top-level feature or layer packages (`billing`, `customers`,
   `infra`, ...). Modules must be siblings: never put a `@Module` inside another module's package. Propose the list
   to the user before writing anything, and agree on the names.
2. **Measure the dependencies between them.** For each candidate, collect the imports of its sources and group them
   by the candidate they point to, for example:
   `grep -rhoE "^import (static )?[a-zA-Z0-9_.]+" src/main/java/com/example/billing | sort | uniq -c`.
   Fully qualified names inside bodies also count. The processor will find them even where `grep` doesn't.
3. **Derive `requires`.** These are the other candidates used, plus one name per external owner:
   - `java.*`: nothing to add;
   - other JDK packages: the JDK module (`javax.swing` → `java.desktop`, `javax.sql` → `java.sql`,
     `javax.annotation.processing` → `java.compiler`, `com.sun.source` → `jdk.compiler`);
   - libraries: the jar's JPMS name. Check it with `jar --describe-module --file <jar>`, or let the build tell you:
     the *Requires violation* message names the module to add;
   - packages with no JPMS name, or that the user wants grouped: an `@ExternalModule`.
4. **Derive `exports`.** Export what other modules actually use. Prefer exporting an API package (`"."` or
   `"x.api"`) over internals. If others use internal classes, flag it to the user as a boundary problem rather than
   exporting everything silently.
5. **Write each `package-info.java`.** It may already exist with Javadoc: keep the Javadoc and add the annotation
   above `package`. Add the `import org.asymetrik.modular.annotations.Module;`.
6. **Build and iterate.** Run a full build (`mvn clean compile`, or `./gradlew clean compileJava`). Read every
   error, fix it with the table below, and repeat until the build passes. Report to the user which fixes were
   boundary decisions (new exports, new requires), as opposed to mechanical ones.

Packages left without `@Module` are fine as long as no module uses them. If a module does, they cause *Unmanaged
dependency*: give them a `@Module`, or an `@ExternalModule`.

## Fixing errors

Messages come from javac, through the processors. Each one names the consumer module, the class used, and the fix.

| Error | Fix |
|-------|-----|
| `Module hierarchy violation: 'x' (...) is nested inside 'y'` | Move the inner module to a sibling package, or remove its `@Module`. |
| `Duplicate module name: 'x' is declared by both ...` | Rename one module or virtual module (and update the `requires` that name it). |
| `Requires violation: 'a' ... uses '<class>' from module 'b' ... Add "b" to the requires of module 'a'` | If the dependency is intended, add `"b"` to `a`'s `requires`. If not, remove the use. Ask the user when unsure: this is an architecture decision. |
| `Export violation: 'a' ... uses '<class>' from module 'b' ... which does not export package 'p'` | Prefer using an exported type of `b`. Otherwise, and only if the user agrees, add `p` to `b`'s `exports`. |
| `Export violation: wildcard import 'p.*' allows ...` | Replace the wildcard import with explicit imports of exported types. |
| `Unmanaged dependency: 'a' ... uses '<class>', which is in no module and has no JPMS name` | Usually one of your own packages without `@Module`: give it a `@Module` and require it. For a third-party package: `@ExternalModule(name = "...", packages = {"p"})`, and require that name. |
| `UnknownRequiredModuleViolation` (ModularVerifier) | Fix the name in `requires`, or remove it. A library that is required but unused also shows up here. |

Don't "fix" violations by moving classes between modules, or by exporting everything, without the user's agreement.
The errors exist to protect boundaries the user chose.

## Requirements: `@Req` and `@TestReq`

`@Req` (repeatable) goes only on a `@Module` package: one EARS sentence per requirement. The subject is the module
name, and `id` is optional but needed to reference it from tests.

| Pattern | Form |
|---------|------|
| Ubiquitous | `The <module> shall <response>.` |
| Event-driven | `When <trigger>, the <module> shall <response>.` |
| State-driven | `While <state>, the <module> shall <response>.` |
| Unwanted behaviour | `If <condition>, then the <module> shall <response>.` |
| Optional feature | `Where <feature is included>, the <module> shall <response>.` |
| Combined | `Where`/`While`/`When` clauses in any order, then optionally `If ..., then`, e.g. `While <state>, when <trigger>, the <module> shall <response>.` |

Writing good requirements:
- One behaviour per requirement. Write it from the code, the tests, or what the user states. Don't invent behaviour
  the code doesn't have: if you derive a requirement from the code, say so, and ask the user to confirm it.
- Use observable responses ("shall return", "shall reject", "shall publish"), not implementation details.
- Ids per module, such as `BIL-1`, `BIL-2`, are a good convention. Ids must be unique.
- The checks: the sentence starts with an uppercase letter; `If` needs `, then`; the subject must be exactly
  `the <module name>` (ignoring case); `shall` (not `must` or `will`).

`@TestReq` goes on a test method or test class, with the ids it checks:

```java
@Test
@TestReq({"BIL-1", "BIL-2"})
void issuesOneInvoicePerPaidOrder() { ... }
```

When the tests compile, an unknown id is an error, and each requirement with an id but no test is a warning:
`Requirement 'BIL-3' of module 'billing' has no test`. To cover a requirement, write or find a test that really
checks the behaviour before adding the `@TestReq`. Don't annotate unrelated tests to silence the warning.

How it works: the main compile writes `META-INF/modular/requirements.properties` (`<id>=<module>|<sentence>`), and
the test compile reads it from the class path. If tests report `Cannot check @TestReq ids`, the specification
processor is not enabled for the main compile.

## Limits to know

- The processors only see the files being compiled. A full build checks everything. An IDE incremental build may
  miss a violation, or show a false coverage warning. Use a full build to confirm.
- Test classes are not checked against module rules: their module's `package-info` is not part of the test
  compilation.
- `@TestReq` can only reference requirements of the same Maven or Gradle project.
- To speed up the module processor at the cost of not checking method bodies, use the javac option
  `-Aorg.asymetrik.modular.treeScan=false`.
