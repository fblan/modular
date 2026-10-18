# Sample: FizzBuzz

A small FizzBuzz application, split into modular `@Module`s, to play with the library.
Its only dependencies are modular's `annotations` and `processor`.

## Modules

Packages are relative to `org.asymetrik.modular.sample.fizzbuzz`.

| Module   | Package  | Exports | Content                                                       |
|----------|----------|---------|---------------------------------------------------------------|
| `rules`  | `rules`  | `"."`   | `Rule` interface and `Rules` factory (`fizz()`, `buzz()`)     |
|          | `rules.internal` | — | `DivisibleByRule`: public in Java, but private to the module |
| `engine` | `engine` | `"."`   | `FizzBuzz`: applies rules to numbers                          |
| `app`    | `app`    | none    | `FizzBuzzApp`: the `main` method                              |

`app` → `engine` → `rules`, and only through exported packages.

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
4. **Nest a module.** Add a `package-info.java` with `@Module` in `rules.internal`. The build fails with a
   *Module hierarchy violation*: modules cannot be nested.
