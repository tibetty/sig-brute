# Contributing to sig-brute

Thank you for your interest in contributing. This project is open source under the
[Apache License 2.0](LICENSE).

## Getting started

**Requirements:** Java 17+, Git.

```bash
git clone https://github.com/tibetty/sig-brute.git
cd sig-brute
./gradlew test          # run the full test suite
./gradlew shadowJar     # build the fat JAR
```

## What to contribute

- Bug fixes and regression tests
- Documentation improvements (README, `designs/`, `decode/ARCHITECTURE.md`, `config-schema.yaml`, examples)
- New example configs or calldata fixtures with tests
- Performance improvements to search or decode paths

Decode tests should live in the package that mirrors production code
(`decode/abi/`, `decode/strategy/`, …). Integration tests for end-to-end decode stay in
`decode/` at the package root.

Please open an issue before large feature work (new subcommands, breaking config
format changes, or new runtime dependencies).

## Coding style

Formatting is manual — there is no automated formatter; CI does not enforce a format check.
Follow these conventions when writing or reviewing code.

If you want to add automated formatting in the future, configure the
[Spotless](https://github.com/diffplug/spotless) plugin in `build.gradle` with a formatter
compatible with the 4-space indentation style above, then restore the `spotlessCheck` step
in `.github/workflows/ci.yml`.

### 1. Indentation

Use **4 spaces** per level. No tabs.

### 2. Continuation lines

When a statement must wrap, indent the continuation by exactly **one extra level** (4 spaces
more than the opening line). Do not align to the column of an opening parenthesis.

```java
// correct
String result = someObject.someMethod(
    firstArgument, secondArgument,
    thirdArgument);

// wrong — aligned to '('
String result = someObject.someMethod(firstArgument, secondArgument,
                                      thirdArgument);
```

### 3. Blank lines between declarations

- One blank line between every method.
- One blank line between the field block and the first method.
- No blank line between consecutive field declarations.

### 4. Use Java 17 language features

Write idiomatic Java 17. Prefer:

| Modern (use)                                              | Legacy (avoid)                                        |
| --------------------------------------------------------- | ----------------------------------------------------- |
| `record` for pure data carriers                           | hand-written value class with all accessors           |
| `sealed` + `permits` for closed hierarchies               | unconstrained inheritance + marker interfaces         |
| Pattern-matching `instanceof` (`if (x instanceof Foo f)`) | cast-then-use after `instanceof`                      |
| `switch` expression with `->` arms                        | `switch` statement with `break` fall-through          |
| `var` when the type is obvious from the initializer       | explicit type when `var` obscures it                  |
| `List.of(...)` / `stream().toList()`                      | `Arrays.asList(...)` / `collect(Collectors.toList())` |
| Text blocks for multi-line string literals                | string concatenation with `\n`                        |

### 5. Blank lines between logic blocks within a method

Separate distinct logical steps with one blank line. Typical boundaries: argument validation,
setup/initialisation, the main loop or computation, and the return.

```java
static List<String> process(List<String> input) {
    if (input == null) {
        throw new IllegalArgumentException("input must not be null");
    }

    List<String> result = new ArrayList<>();
    for (String s : input) {
        result.add(s.trim());
    }

    return result;
}
```

### 6. Always use braces

Every `if`, `else`, `for`, `while`, and `do` body must be wrapped in `{ }`, even when it is a
single statement.

```java
// correct
if (condition) {
    doSomething();
}

// wrong
if (condition)
    doSomething();
```

## Other guidelines

- Match existing style: minimal dependencies, clear names, small focused methods.
- Add or update tests for behavior changes (`src/test/java/`).
- Run `./gradlew test` before opening a pull request.
- Do not commit IDE metadata (`.idea/`, `.vscode/`, `.classpath`), build output,
  or local AI session logs (`.memory/`).

## Pull requests

1. Fork and create a branch from `main`.
2. Keep commits focused; write clear commit messages.
3. Ensure CI passes (GitHub Actions runs `./gradlew test` on Java 17). Releases are published to Maven Central when a `v*` tag is pushed (see `designs/public_api.md`).
4. Describe what changed and how you tested it in the PR body.

## Reporting issues

Include:

- Java version (`java -version`)
- Command line and config YAML (redact secrets)
- Expected vs actual behavior
- Minimal reproduction if possible

## License

By contributing, you agree that your contributions will be licensed under the
Apache License 2.0, consistent with the rest of the project.
