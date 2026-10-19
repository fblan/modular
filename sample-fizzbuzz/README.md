# Sample: FizzBuzz

A small FizzBuzz application, split into modular `@Module`s, to play with the library.
Its only dependencies are modular's `annotations`, `processor` and `specification`.

## Modules

Packages are relative to `org.asymetrik.modular.sample.fizzbuzz`.

| Module   | Package  | Exports | Requires            | Content                                                   |
|----------|----------|---------|---------------------|-----------------------------------------------------------|
| `rules`  | `rules`  | `"."`   | none                | `Rule` interface and `Rules` factory (`fizz()`, `buzz()`) |
|          | `rules.internal` | — |                  | `DivisibleByRule`: public in Java, but private to the module |
| `engine` | `engine` | `"."`   | `"rules"`           | `FizzBuzz`: applies rules to numbers                      |
| `app`    | `app`    | none    | `"engine", "rules"` | `FizzBuzzApp`: the `main` method                          |

A module may only use the modules it lists in `requires`, and only their exported packages.
`app` uses `Rules` directly, so it requires `rules` itself: `requires` is not transitive.
Outside the modules, only `java.*` can be used freely. Any other package must be required, either by its JPMS
module name (a JDK module such as `java.desktop`, or a library jar's module name such as `jakarta.validation`),
or through a virtual module declared with `@ExternalModule`. A package of yours without `@Module` has no JPMS
name, so it needs an `@ExternalModule`.
The sample only uses `java.*`.

## Requirements

Each module states what it must do with `@Req`, next to `@Module`, as sentences in EARS format. For example, in
`engine/package-info.java`:

```java
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
@Req(id = "ENG-2", value = "When several rules match a number, the engine shall concatenate their words in rule order.")
@Req(id = "ENG-3", value = "While the upper bound is below 1, the engine shall say nothing.")
```

`app` also uses the *Where* (optional feature) and *If …, then* (unwanted behaviour) patterns, and `rules` the
plain *The … shall* one.

The unit tests reference the requirements they check with `@TestReq`, for example in `FizzBuzzTest`:

```java
@Test
@TestReq("ENG-2")
void concatenatesTheWordsInRuleOrder() { ... }
```

When the tests compile, an unknown id is an error, and a requirement without a test is a warning. `APP-1` has no
test on purpose, so `mvn clean install` shows:

```
[WARNING] Requirement 'APP-1' of module 'app' has no test: "Where no argument is given, the app shall print FizzBuzz up to 100.". Add @TestReq("APP-1") to a test that checks it.
```

## Build and run

From the repository root:

```bash
mvn clean install
java -cp sample-fizzbuzz/target/classes org.asymetrik.modular.sample.fizzbuzz.app.FizzBuzzApp 15
```

The module rules and the requirements are checked when the code compiles, by the two annotation processors
declared in `annotationProcessorPaths` in `pom.xml`. Neither `@Module` nor `@Req` is needed at runtime.

## Things to try

Rebuild with `mvn compile -pl sample-fizzbuzz` after each change.

1. **Use an internal class.** In `FizzBuzzApp`, replace `Rules.buzz()` with
   `new DivisibleByRule(5, "Buzz")` (import `rules.internal.DivisibleByRule`). The build fails with an
   *Export violation*, because `rules` does not export `rules.internal`.
2. **Export it.** Add `"org.asymetrik.modular.sample.fizzbuzz.rules.internal"` (or
   `"org.asymetrik.modular.sample.fizzbuzz.rules.*"`) to the exports of `rules/package-info.java`. The build passes again.
3. **Close a module.** Change `engine` to `exports = {}`. `app` can no longer use `FizzBuzz`.
4. **Drop a requires.** Remove `"rules"` from the requires of `app/package-info.java`. The build fails with a
   *Requires violation*: `app` uses `Rules` but does not declare it needs the `rules` module, even though
   `engine` requires it.
5. **Use an external package.** In `FizzBuzzApp`, replace `forEach(System.out::println)` with
   `forEach(line -> javax.swing.JOptionPane.showMessageDialog(null, line))`. The build fails with a
   *Requires violation*: `javax.swing` is not `java.*`, and it belongs to the JDK module `java.desktop`. Add
   `"java.desktop"` to the requires of `app` and the build passes.

   To group packages under a name of your own instead, declare a virtual module in a new
   `external/package-info.java`, and require `"swing"` instead of `"java.desktop"`:
   ```java
   @ExternalModule(name = "swing", packages = {"javax.swing"})
   package org.asymetrik.modular.sample.fizzbuzz.external;

   import org.asymetrik.modular.annotations.ExternalModule;
   ```
6. **Nest a module.** Add a `package-info.java` with `@Module` in `rules.internal`. The build fails with a
   *Module hierarchy violation*: modules cannot be nested.
7. **Break a requirement.** In `engine/package-info.java`, change ENG-3 to
   `"While the upper bound is below 1, the system shall say nothing."`. The build fails: the subject of a
   requirement must be its module, *the engine*. Then try a sentence that is not EARS, such as
   `"The engine must say nothing."`: the error lists the EARS patterns to use.
8. **Reference a missing requirement.** In `FizzBuzzTest`, change `@TestReq("ENG-3")` to `@TestReq("ENG-33")` and
   run `mvn test-compile -pl sample-fizzbuzz`. The test build fails: no `@Req` has this id. Back to `"ENG-3"`, then
   remove the `@TestReq`: the build passes again, with a warning that ENG-3 has no test.
