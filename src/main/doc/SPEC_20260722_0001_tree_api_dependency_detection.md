# SPEC_20260722_0001: Tree API Dependency Detection (Method Bodies, Imports & String Literals)

**Date**: 2026-07-22
**Status**: Implemented
**Author**: Project Team
**Version**: 2.0
**Related**: [Issue #10](https://github.com/fblan/modular/issues/10), export violation detection (commit 97b5e1d)

---

## Part 1: Feature Description

### Overview
`ModuleAnnotationProcessor` detects cross-module export violations by walking the `javax.lang.model` Element/TypeMirror API: superclass, interfaces, field types, and method signatures (parameter/return/thrown types). This feature extends that detection using the Java Compiler Tree API (`com.sun.source.tree`/`com.sun.source.util`) to also see dependencies invisible to the Element API: local variable declarations, static-imported calls, qualified static access, string literals naming a class, and the file's own import declarations — including flagging a wildcard import as the violation itself when it is what let a forbidden class resolve at all.

### Purpose
- Close the detection gap where a class references a non-exported type only through a local variable or a fully-qualified inline reference, without it ever appearing in a field or method signature.
- Detect static-imported usage (`import static other.module.Utils.doThing; ... doThing();`) — a call site that names no type anywhere in the file except the import line.
- Detect a common evasion/reflection pattern: naming a type as a plain string literal (e.g. `Class.forName("other.module.Secret")`).
- Treat import declarations themselves as first-class evidence of a dependency — an explicit import of a non-exported type is a violation regardless of whether the type is subsequently used in the body.
- Flag a wildcard import (`import other.module.*;`) as the violation site, on the import statement itself, when it is the only reason a non-exported class resolved by simple name — rather than just reporting the (indirect) usage site.
- Allow disabling this additional scanning via a processor option, for when compile-time cost matters more than full coverage (e.g. off for fast local builds, on for CI) — the baseline Element/TypeMirror signature scan is unaffected by this toggle and always runs.
- Keep the check compile-time (annotation-processing phase), consistent with the existing mechanism — no post-compile bytecode step, no new third-party dependency.

### Problem Statement

#### Without This Feature
```java
package consumer.module;

import static provider.module.internal.Utils.doThing;

public class Service {
    public void run() {
        var secret = new provider.module.internal.Secret(); // local var — invisible
        Class.forName("provider.module.internal.Secret");   // string literal — invisible
        doThing();                                           // static-imported call — invisible
    }
}
```
None of these three lines appear in `Service`'s signature (no field, no method parameter/return/throws), so `collectReferencedTypes` never sees `provider.module.internal.Secret` or `provider.module.internal.Utils`, and the export violations go unreported even though `provider.module` does not export `internal`.

#### With This Feature
All three forms are detected. Import-derived and body-derived type/member references are reported with the same `ERROR` diagnostic style as signature-level violations (tagged with their detection source); string-literal matches are tagged as heuristic; and a wildcard import that is itself responsible for resolving a forbidden class gets its own diagnostic anchored on the `import` line.

### Scope

**In scope:**
1. Any identifier or qualified reference in a class body resolving (via `Trees.getElement`) to a type, or to a field/method/constructor whose *enclosing type* is the dependency — this covers local variable declarations (including generic type arguments, since each type argument is its own identifier node), static-imported calls, qualified static access (`Utils.thing()` with no import at all), and fully-qualified inline usage with no import.
2. String literal constants (`LiteralTree` of kind `STRING_LITERAL`) whose value exactly matches a class known to the compilation.
3. Import declarations: explicit type imports and specific static-member imports resolve directly to their type and are treated as an independent, direct signal of dependency (flagged even if the type turns out unused in the body). A static wildcard import (`import static Utils.*;`) names one specific class and is treated the same way.
4. Non-static wildcard (type-import-on-demand) imports (`import other.module.*;`): cannot name a specific class on their own, so they are cross-referenced against types actually found via (1) above; if a match is a violation, the diagnostic is anchored on the wildcard `import` statement itself, not the usage site. Per JLS, a type-import-on-demand only covers the named package's direct members (not sub-packages), and the cross-check matches that boundary exactly — a class in a sub-package is never wrongly attributed to the wildcard.
5. A javac option, `-Aorg.asymetrik.modular.treeScan=<true|false>` (default `true`), to disable all of the above (items 1–4) in one switch — e.g. for local builds where compile time matters more. The original signature-based Element/TypeMirror scan is never affected by this option.

**Out of scope (explicitly, to keep this increment bounded):**
- Non-literal string values: concatenation (`"provider.module." + "Secret"`), computed strings, or a reference to a constant declared elsewhere (`Class.forName(SOME_CONST)`) — only a directly-written string-literal token is matched; javac's own constant-folding is not resolved back to a source class.
- Binary names for nested/inner classes in string literals (e.g. `Class.forName("Outer$Inner")`) — `Elements.getTypeElement` expects the canonical dotted form (`Outer.Inner`), so only that form is matched, not the `$`-separated binary name reflection APIs actually require at runtime.
- Per-mechanism granularity: `-Aorg.asymetrik.modular.treeScan` is all-or-nothing across import scanning, body scanning, and wildcard flagging together — no finer-grained option to disable just one of them in this increment.
- Any non-javac compiler (this relies on `com.sun.source.util.Trees`, a javac-specific, JSR-269-adjacent API).

### Use Cases

#### Use Case 1: Fully-Qualified Local Variable, No Import
```java
public void run() {
    provider.module.internal.Secret s = new provider.module.internal.Secret();
}
```
**Expected**: Violation reported, tagged "type/member reference in method body".

#### Use Case 2: String Literal Violation
```java
public void run() {
    Class.forName("provider.module.internal.Secret");
}
```
**Expected**: Violation reported, tagged "string literal reference — heuristic match, verify this is intentional".

#### Use Case 3: Arbitrary String Literal — No False Positive
```java
public void run() {
    log.info("some/random/path");
}
```
**Expected**: No violation — the literal does not resolve to any known type.

#### Use Case 4: Explicit Import of a Non-Exported Type, Even If Unused
```java
import provider.module.internal.Secret;
public class Service { }
```
**Expected**: Violation reported, tagged "import declaration" — the import alone is sufficient evidence.

#### Use Case 5: Static-Imported Call With No Local Variable
```java
import static provider.module.internal.Utils.doThing;
public class Service { void run() { doThing(); } }
```
**Expected**: Violation reported (via the import-declaration path, since the static import itself names the exact class `Utils`) — a case entirely invisible to both the original signature scan and to local-variable-only scanning.

#### Use Case 6: Wildcard Import Enabling a Forbidden Class — Flagged on the Import
```java
import provider.module.*;
public class Service { void run() { Secret s = new Secret(); } }
```
**Expected**: Violation reported **anchored on the `import provider.module.*;` line**, message names the wildcard explicitly (e.g. "Export violation: wildcard import 'provider.module.*' allows ... to use 'provider.module.Secret' ..."), not just the usage site.

#### Use Case 7: Local Variable / Import of an Exported Type — Valid
```java
provider.module.PublicApi api = new provider.module.PublicApi();
```
**Expected**: No violation.

#### Use Case 8: Generic Local Variable Type Argument
```java
List<provider.module.internal.Secret> secrets = new ArrayList<>();
```
**Expected**: Violation detected — each generic type argument is its own identifier node in the tree and is resolved independently, no special generics-handling code needed.

#### Use Case 9: Tree Scan Disabled
Compiled with `-Aorg.asymetrik.modular.treeScan=false`:
- A **field-typed** (signature-level) violation is still reported (baseline scan unaffected).
- A **local-variable-only** (no import) violation is **not** reported (tree scan skipped entirely).

### Benefits
1. Closes real detection gaps (local-only usage, static-import-only usage, wildcard-obscured usage) without introducing bytecode tooling or a third-party AST library.
2. Treats import declarations as first-class dependency evidence — cheaper and more precise than body-scanning for the common explicit-import case.
3. Points the developer directly at the *cause* (the wildcard import) rather than just a symptom (the usage site) when a wildcard import is what permitted a forbidden class.
4. Stays within javac's own supported (if non-standard) Tree API surface; no `--add-exports` needed since `com.sun.source.tree`/`com.sun.source.util` are unconditionally exported by `jdk.compiler`.
5. Configurable cost: full detection on CI, fast baseline-only checks locally, via a single `-A` option.

---

## Part 2: Implementation Description

### High-Level Flow
1. `init(ProcessingEnvironment)` reads the `org.asymetrik.modular.treeScan` option (default enabled); if disabled, or `Trees.instance(...)` isn't available (non-javac), `trees` stays `null` and all tree-based detection short-circuits to empty results — the original signature scan is untouched.
2. For each `TypeElement` in `checkExportViolations`, `collectTreeReferencedTypes` runs once per class, and returns a `TreeScanResult` with: `referencedTypes` (from body identifier/member-select resolution), `stringLiteralTypes`, `importedTypes` (explicit + static-specific + static-wildcard imports), `wildcardImports` (non-static package wildcards, each paired with its `ImportTree`), and the `CompilationUnitTree`.
3. Import classification (`collectImports`): for each `ImportTree`,
   - static + wildcard (`import static Utils.*;`) → resolve the named type directly (strip `.*`) → `importedTypes`.
   - static + specific (`import static Utils.thing;`) → resolve the member's element, take its enclosing type → `importedTypes`.
   - non-static + wildcard (`import pkg.*;`) → record as a `WildcardImport(packageName, importTree)` — cannot resolve a specific class from the import alone.
   - non-static + specific (`import pkg.Foo;`) → resolve directly → `importedTypes`.
4. Body scan: a `TreePathScanner` rooted at the class's own `TreePath` (skipping nested classes, which are scanned separately as their own root elements):
   - `visitIdentifier` / `visitMemberSelect`: resolve `trees.getElement(getCurrentPath())`, then `resolveOwningType(Element)` — `CLASS/INTERFACE/ENUM/RECORD/ANNOTATION_TYPE` → itself; `FIELD/METHOD/CONSTRUCTOR/ENUM_CONSTANT` → its enclosing type (this is what makes an unqualified static-imported call resolve to its owning class); anything else (locals, parameters, packages) → ignored. This single mechanism covers local variable types, generic type arguments (each is its own identifier node), qualified/unqualified static access, and fully-qualified inline usage — no separate local-variable-specific logic needed.
   - `visitLiteral`: string literals resolved via `processingEnv.getElementUtils().getTypeElement(value)`.
5. Reporting (`reportExportViolationIfAny`), for each candidate dependency in order — signature-based (untagged), `importedTypes` ("import declaration"), `referencedTypes` ("type/member reference in method body"), `stringLiteralTypes` (heuristic) — deduplicated per consumer type by the dependency's qualified name (first detection wins, so an explicit import takes precedence over a redundant body-scan hit for the same type):
   - if the dependency is a violation, not already explicitly imported, and its package matches a recorded `WildcardImport` for this compilation unit → emit the ERROR **on the wildcard `ImportTree`** via `Trees.printMessage(Kind, CharSequence, Tree, CompilationUnitTree)`, message names the wildcard explicitly, and skip the normal per-usage diagnostic for that dependency.
   - otherwise → the existing usage-site diagnostic, anchored on the consumer `TypeElement`, with a `[detected via: ...]` suffix when the detection source isn't the original signature scan.

### Key API Points
- `Trees.instance(ProcessingEnvironment)` — entry point; null-safe fallback if unavailable (non-javac) or disabled via the option.
- `Trees.getElement(TreePath)` — resolves a declaration or type-use path to its `Element`; used for both body identifiers and import qualified-identifiers. (Not `Trees.getTypeMirror`, which only reads a possibly-unattributed cached type field and has no attribution fallback — confirmed by reading `JavacTrees`/`Attr` source; `getElement` forces attribution on demand via `attr.attribClass(...)` when needed.)
- `TreePath.getPath(CompilationUnitTree, Tree)` — builds the path needed to resolve an import's qualified identifier.
- `Trees.printMessage(Diagnostic.Kind, CharSequence, Tree, CompilationUnitTree)` — anchors a diagnostic on an arbitrary `Tree` node (the wildcard `ImportTree`), not just an `Element`; this is what makes "flag the import itself" possible.
- No `--add-exports` required: `com.sun.source.tree`/`com.sun.source.util` are unconditionally exported by `jdk.compiler`.

### Diagnostic Message Changes
Standard (usage-site) message gains an optional suffix:
```
... [detected via: import declaration]
... [detected via: type/member reference in method body]
... [detected via: string literal reference — heuristic match, verify this is intentional]
```
Wildcard-caused violations get a distinct message anchored on the import line instead:
```
Export violation: wildcard import 'provider.module.*' allows 'Service' (consumer.module) to use 'provider.module.Secret'
from module 'provider' (provider.module) which does not export package 'provider.module'. Replace the wildcard with an
explicit import of only exported types, or add 'provider.module' to module 'provider' exports.
```

### Configuration
- Option key: `org.asymetrik.modular.treeScan` (declared via `@SupportedOptions`).
- Pass `-Aorg.asymetrik.modular.treeScan=false` via `maven-compiler-plugin`'s `compilerArgs` to disable for a given build (e.g. a fast local Maven profile); omit (or `=true`) to keep it enabled, which is the default and recommended for CI.

### Implementation Location
- **File**: `processor/src/main/java/org/asymetrik/modular/processor/ModuleAnnotationProcessor.java`
- **New/changed methods**: `init` (option handling), `collectTreeReferencedTypes`, `collectImports`, `resolveOwningType`, `addIfNew`, `reportExportViolationIfAny` (wildcard cross-check), `reportWildcardViolation`.
- **New records**: `WildcardImport`, updated `TreeScanResult`.

### Test Data Structure
Tests live in `processor/src/test/java/org/asymetrik/modular/processor/ModuleAnnotationProcessorTest.java`, using the existing in-memory `JavaFileObject` + `ToolProvider.getSystemJavaCompiler()` + `-proc:only` harness. The `compile(...)` helper gained an overload accepting extra `-A` compiler options, needed for the disable-tree-scan test.

---

## Unit Tests (implemented)
1. `fullyQualifiedLocalVariableWithoutImport_producesExportViolationError` — Use Case 1.
2. `stringLiteralMatchingNonExportedType_producesExportViolationError` — Use Case 2.
3. `unrelatedStringLiteral_producesNoErrors` — Use Case 3.
4. `explicitlyImportedNonExportedType_producesExportViolationErrorEvenWithoutUsage` — Use Case 4.
5. `staticImportedCallWithoutQualifierOrLocalVariable_producesExportViolationError` — Use Case 5.
6. `wildcardImportAllowingNonExportedType_isFlaggedOnTheImportItself` — Use Case 6.
7. `localVariableReferencingExportedType_producesNoErrors` — Use Case 7.
8. `genericLocalVariableTypeArgumentReferencingNonExportedType_producesExportViolationError` — Use Case 8.
9. `treeScanDisabled_stillDetectsSignatureViolation_butNotBodyOnlyViolation` — Use Case 9.

All 16 tests in the class pass (7 pre-existing + 9 above, with 2 of the original tree-scan tests superseded/renamed in this revision); full `mvn clean install` is green.

---

## Implementation Checklist
- [x] Add Tree API imports (`CompilationUnitTree`, `ImportTree`, `IdentifierTree`, `MemberSelectTree`, etc.)
- [x] `@SupportedOptions` + `init()` option handling for `org.asymetrik.modular.treeScan`
- [x] Generalized `visitIdentifier`/`visitMemberSelect` body scan (replacing the narrower `visitVariable`-only approach)
- [x] Import classification (explicit / static / wildcard)
- [x] Wildcard cross-check + `Trees.printMessage` anchored diagnostic
- [x] Guard nested-class re-descent
- [x] 9 unit tests covering all use cases above
- [ ] Update `claude.md` with the new detection capability (processor module isn't yet documented there at all — worth a follow-up pass covering the whole `processor` module, not just this feature)
