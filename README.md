# modular

Modules for Java packages, checked when your code compiles.

**modular** lets you split one Java code base into modules, without JPMS `module-info.java` and without splitting it
into several Maven or Gradle projects. You declare a module with an annotation on a `package-info.java`, saying which
packages it exports and which modules it requires. An annotation processor then rejects, at compile time, any code
that crosses a module boundary it shouldn't.

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
package com.example.engine;

import org.asymetrik.modular.annotations.Module;
```

```
[ERROR] FizzBuzzApp.java:[26,8] Requires violation: 'app' (com.example.app) uses 'com.example.rules.Rules'
        from module 'rules' (com.example.rules) without requiring it. Add "rules" to the requires of module 'app' ...
```

It also lets each module state its **requirements**, as sentences in [EARS](https://alistairmavin.com/ears/) format,
and link unit tests to them:

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
package com.example.engine;
```

```java
@Test
@TestReq("ENG-1")
void saysTheNumberWhenNoRuleMatches() { ... }
```

## Contents

- [Getting started](#getting-started)
- [Declaring modules](#declaring-modules)
- [Requirements](#requirements)
- [Checking in tests with ModularVerifier](#checking-in-tests-with-modularverifier)
- [Errors and how to fix them](#errors-and-how-to-fix-them)
- [IDE support](#ide-support)
- [Claude Code skill](#claude-code-skill)
- [Building modular](#building-modular)

## Getting started

Requires Java 21 or later. Artifacts are on Maven Central, under the group `org.asymetrik.modular`.

| Artifact        | What it contains                                                               | Needed at       |
|-----------------|--------------------------------------------------------------------------------|-----------------|
| `annotations`   | `@Module`, `@ExternalModule`                                                   | compile time    |
| `processor`     | `ModuleAnnotationProcessor`: checks the module rules                           | compile time    |
| `specification` | `@Req`, `@TestReq`, and `RequirementProcessor`: checks the requirements         | compile time    |
| `verification`  | `ModularVerifier`: the same module rules, checked from a test with ArchUnit    | tests (optional) |

### Maven

```xml
<properties>
    <modular.version>0.1.0</modular.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.asymetrik.modular</groupId>
        <artifactId>annotations</artifactId>
        <version>${modular.version}</version>
        <scope>provided</scope>
    </dependency>
    <!-- Optional: requirements (@Req, @TestReq) -->
    <dependency>
        <groupId>org.asymetrik.modular</groupId>
        <artifactId>specification</artifactId>
        <version>${modular.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <!-- Since JDK 23, javac no longer finds processors on the class path by itself. -->
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.asymetrik.modular</groupId>
                        <artifactId>processor</artifactId>
                        <version>${modular.version}</version>
                    </path>
                    <!-- Optional: requirements -->
                    <path>
                        <groupId>org.asymetrik.modular</groupId>
                        <artifactId>specification</artifactId>
                        <version>${modular.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The annotations are only read at compile time, so `provided` keeps them out of your runtime class path.

### Gradle

```kotlin
dependencies {
    compileOnly("org.asymetrik.modular:annotations:0.1.0")
    annotationProcessor("org.asymetrik.modular:processor:0.1.0")

    // Optional: requirements
    compileOnly("org.asymetrik.modular:specification:0.1.0")
    testCompileOnly("org.asymetrik.modular:specification:0.1.0")
    annotationProcessor("org.asymetrik.modular:specification:0.1.0")
    testAnnotationProcessor("org.asymetrik.modular:specification:0.1.0")
}
```

### Try it on the sample

[`sample-fizzbuzz`](sample-fizzbuzz/README.md) is a small application split into three modules, with requirements and
tests. Its README lists experiments that trigger each kind of error.

## Declaring modules

### `@Module`

A module is a package annotated with `@Module` in its `package-info.java`. It contains that package and all its
sub-packages.

```java
@Module(
    name = "billing",                                   // unique name, used in other modules' requires
    exports = {".", "com.example.billing.api.*"},       // packages other modules may use
    requires = {"customers", "java.sql", "slf4j"}       // modules this module may use
)
package com.example.billing;

import org.asymetrik.modular.annotations.Module;
```

| Attribute  | Meaning |
|------------|---------|
| `name`     | The module's name. Must be unique: other modules reference it in `requires`. |
| `exports`  | Packages that other modules may use. `"."` is the module's own package (not its sub-packages). A full package name exports exactly that package. `"com.example.billing.api.*"` exports the sub-packages of `com.example.billing.api`. `{}` exports nothing. |
| `requires` | Names of the modules this module may use: other `@Module`s, `@ExternalModule`s, or JPMS module names. Defaults to `{}`. |

### The rules

1. **No nested modules.** A module's package may not contain another `@Module` package, at any depth. Modules are
   peers: `com.example.billing` and `com.example.customers`, not `com.example.billing.customers`.
2. **Exports.** A class may only use another module's classes from packages that module exports.
3. **Requires.** A class may only use another module if its own module lists that module in `requires`.
   `requires` is not transitive: if `app` requires `engine` and `engine` requires `rules`, `app` must also require
   `rules` to use it.
4. **Only `java.*` is free.** Outside your modules, only `java` and `java.*` packages can be used without being
   required. Anything else (`javax.*`, `jakarta.*`, libraries, or your own packages without `@Module`) must belong
   to a module that is required, as described below.

The processor finds dependencies in signatures (fields, parameters, return types, supertypes), in method bodies
(local variables, calls, static imports, fully qualified names), in import declarations (including wildcard
imports), and in string literals that name a class. Body scanning can be turned off with the javac option
`-Aorg.asymetrik.modular.treeScan=false`, for faster builds; signatures are always checked.

### External dependencies: JPMS names and `@ExternalModule`

A class outside `java.*` and outside your modules belongs to the first of:

1. **An `@ExternalModule`** that covers its package (see below).
2. **Its JPMS module**:
   - for the JDK and the module path, its named module: `java.desktop` for `javax.swing`, `java.sql` for
     `javax.sql`, `jdk.compiler` for `com.sun.source`;
   - for a jar on the class path, the name JPMS would give it: its `module-info`, else its `Automatic-Module-Name`,
     else a name derived from the file name. For example `jakarta.validation` for `jakarta.validation-api.jar`.

So in most cases, requiring the JPMS name is enough:

```java
@Module(name = "app", exports = {}, requires = {"engine", "java.desktop", "jakarta.validation"})
```

To group packages under a name of your own, or for packages with no JPMS name, declare a **virtual module** with
`@ExternalModule`, on any `package-info.java`:

```java
@ExternalModule(name = "slf4j", packages = {"org.slf4j"})
@ExternalModule(name = "inject", packages = {"jakarta.inject"})
package com.example.modules;

import org.asymetrik.modular.annotations.ExternalModule;
```

- A listed package also covers its sub-packages: `"org.slf4j"` covers `org.slf4j.event`.
- Everything in a virtual module is exported.
- When several virtual modules cover a package, the most specific one wins. A real `@Module` always wins.
- Virtual module names share the namespace of module names: they must be unique.

A class with none of these owners, such as one of your own packages without `@Module`, is an *Unmanaged dependency*.

## Requirements

The `specification` artifact adds `@Req`: requirements of a module, written next to its `@Module`.

### `@Req`

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
@Req(id = "ENG-2", value = "When several rules match a number, the engine shall concatenate their words in rule order.")
@Req("While the upper bound is below 1, the engine shall say nothing.")
package com.example.engine;

import org.asymetrik.modular.annotations.Module;
import org.asymetrik.modular.specification.Req;
```

Each requirement is one sentence in EARS format, and its subject is the module:

| Pattern            | Form |
|--------------------|------|
| Ubiquitous         | The *engine* shall *&lt;response&gt;*. |
| Event-driven       | When *&lt;trigger&gt;*, the *engine* shall *&lt;response&gt;*. |
| State-driven       | While *&lt;state&gt;*, the *engine* shall *&lt;response&gt;*. |
| Unwanted behaviour | If *&lt;condition&gt;*, then the *engine* shall *&lt;response&gt;*. |
| Optional feature   | Where *&lt;feature is included&gt;*, the *engine* shall *&lt;response&gt;*. |
| Combined           | While *&lt;state&gt;*, when *&lt;trigger&gt;*, the *engine* shall *&lt;response&gt;*. |

`RequirementProcessor` checks that `@Req` is on a `@Module` package, that each sentence follows a pattern, that its
subject is the module (ignoring case), and that ids are unique. Ids are optional; set one to reference the
requirement from tests.

### `@TestReq`

```java
class FizzBuzzTest {

    @Test
    @TestReq("ENG-1")
    void saysTheNumberWhenNoRuleMatches() { ... }

    @Test
    @TestReq({"ENG-2", "RUL-1"})
    void concatenatesTheWordsInRuleOrder() { ... }
}
```

`@TestReq` goes on a test method, or on a test class to cover all its tests. When the main code compiles, the
processor writes the requirements with an id to `META-INF/modular/requirements.properties`. When the tests compile,
it reads that file from the class path:

- an id that matches no `@Req` is an **error**, on the test;
- a requirement with an id that no test covers is a **warning**:
  `Requirement 'APP-1' of module 'app' has no test: "...". Add @TestReq("APP-1") to a test that checks it.`

These checks only run in compilations that contain at least one `@TestReq`.

## Checking in tests with ModularVerifier

`verification` checks the same module rules on compiled classes with [ArchUnit](https://www.archunit.org/), from a
test. It also reports names in `requires` that match no module, which the processor cannot do reliably.

```xml
<dependency>
    <groupId>org.asymetrik.modular</groupId>
    <artifactId>verification</artifactId>
    <version>${modular.version}</version>
    <scope>test</scope>
</dependency>
```

```java
@Test
void modulesAreRespected() {
    ModularVerifier.Result result = new ModularVerifier("com.example").verify();

    assertThat(result).isInstanceOf(ModularVerifier.Valid.class);
}
```

`verify()` returns `Valid`, or the first violation found: `NestedModuleViolation`, `DuplicateModuleNameViolation`,
`UnknownRequiredModuleViolation`, `RequiresViolation`, `ExportViolation` or `UnmanagedDependencyViolation`. Each
violation has a `formatError()` method with a readable message.

## Errors and how to fix them

| Error | Cause | Fix |
|-------|-------|-----|
| **Module hierarchy violation** | A `@Module` package is inside another one. | Move the inner module to a sibling package, or remove its `@Module`. |
| **Duplicate module name** | Two modules (or virtual modules) share a name. | Rename one of them. |
| **Requires violation** | A class uses a module that its module does not require. | Add the name given in the message to `requires`, or stop using the class. |
| **Export violation** | A class uses a package that its module does not export. | Use an exported class instead, or export the package from the providing module. When reported on a wildcard import, replace the wildcard with explicit imports of exported types. |
| **Unmanaged dependency** | A class uses a package outside `java.*` that belongs to no module, no virtual module, and has no JPMS name. | Declare an `@ExternalModule` covering the package and require it, or give your own package a `@Module`. |
| **Unknown required module** (`ModularVerifier`) | A name in `requires` matches nothing. | Fix the name, or remove it. |
| **Requirement … is not an EARS sentence** | A `@Req` does not follow an EARS pattern. | Rewrite it with one of the forms listed in the message. |
| **Requirement … has '…' as subject** | The sentence names another system. | Write "the &lt;module name&gt; shall …". |
| **Requirement on package …, which is not a @Module** | `@Req` without `@Module`. | Move it to the module's `package-info.java`. |
| **Duplicate requirement id** | Two `@Req`s share an id. | Give one a new id. |
| **Unknown requirement id … in @TestReq** | The test references an id that no `@Req` has. | Fix the id, or add the requirement. |
| **Requirement … has no test** (warning) | No `@TestReq` references the requirement. | Add `@TestReq("<id>")` to a test that checks it. |

## IDE support

The processors run in any build that runs javac with them: Maven, Gradle, and IntelliJ IDEA after importing the
Maven or Gradle project.

The processors only see what is being compiled. A full build (`mvn clean compile`, or **Build → Rebuild Project**)
checks everything. An incremental build in the IDE that recompiles a single class may miss a violation, because the
`package-info.java` declaring its module is not recompiled with it. It may also show a coverage warning for
requirements that other, not recompiled, tests cover. Run a full build, or rely on your CI, before you merge.

## Claude Code skill

This repository is also a [Claude Code](https://claude.com/claude-code) plugin marketplace. Its `modular` plugin has
a skill that helps you use modular in your own project: set up the build, declare modules from your existing
packages, find what each module must require and export, fix violations, and write EARS requirements with their
tests.

```
/plugin marketplace add fblan/modular
/plugin install modular@asymetrik
```

Then ask Claude, for example, *"split this project into modular modules"* or *"why do I get an Unmanaged
dependency?"*, or run the skill directly with `/modular:modular`.

Without the plugin system, copy [`claude/plugins/modular/skills/modular`](claude/plugins/modular/skills/modular)
into your project's `.claude/skills/` directory, or into `~/.claude/skills/` for all your projects.

## Building modular

```bash
mvn clean install
```

| Module            | Description |
|-------------------|-------------|
| `annotations`     | `@Module`, `@ExternalModule` |
| `processor`       | Annotation processor for the module rules |
| `verification`    | `ModularVerifier`, ArchUnit-based |
| `specification`   | `@Req`, `@TestReq` and their annotation processor |
| `sample-fizzbuzz` | Sample application (not published) |

Design notes for each feature are in [`src/main/doc`](src/main/doc). Releases are made with `release.sh`.

## License

[Apache License 2.0](LICENSE).
