# Sample: FizzBuzz

A small FizzBuzz application, split into modular `@Module`s, to play with the library.
Its only dependencies are modular's `annotations` and `processor`.

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

## Build and run

From the repository root:

```bash
mvn clean install
java -cp sample-fizzbuzz/target/classes org.asymetrik.modular.sample.fizzbuzz.app.FizzBuzzApp 15
```

The module rules are checked when the code compiles, by the annotation processor declared in
`annotationProcessorPaths` in `pom.xml`. The `@Module` annotation isn't needed at runtime.

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
