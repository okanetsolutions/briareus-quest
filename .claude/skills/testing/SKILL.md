---
name: testing
description: How to run and write the checks in this repo: the JUnit core tests with the FakeServer interceptor, detekt, Android lint with warnings as errors, editorconfig and actionlint, and the local JDK and Android SDK setup. Use whenever adding code to core/, fixing a bug, or asked to run the tests or verify a change.
---

# Checks in this repo

```sh
./gradlew check -Pwerror        # the CI gate: core tests, detekt (both modules), app lint, warnings as errors
./gradlew :core:test            # just the core tests
./gradlew :core:test --tests 'com.okanetsolutions.briareus.core.ApiClientTest'
./gradlew detekt                # static analysis only, reports under */build/reports/detekt/
./gradlew assembleDebug assembleRelease -Pwerror   # what CI builds besides check
```

CI also runs `editorconfig-checker` (LF, final newline, no trailing spaces; config in `.editorconfig-checker.json`),
`actionlint` over `.github/workflows`, CodeQL, and the Gradle wrapper checksum. Markdown keeps trailing spaces exempt.

## Toolchain on this machine

Gradle 8.14 needs JDK 17+; the system default here is JDK 8. `.claude/settings.local.json` (not committed) sets
`JAVA_HOME` to the Temurin 21 under `~/.jdks` and `ANDROID_HOME` to `~/Android/Sdk`. In a plain shell:

```sh
export JAVA_HOME=$(echo ~/.jdks/jdk-21*) ANDROID_HOME=~/Android/Sdk
```

This is an ARM64 Linux box, so `~/.gradle/gradle.properties` points `android.aapt2FromMavenOverride` at the SDK's own
`aapt2`; the "experimental" warning it prints is expected.

## Writing core tests

Tests live in `core/src/test/kotlin/.../core/`, one file per core file, JUnit 4, `kotlinx.coroutines.test.runTest`
for suspending code. Test names are sentences in camel case: `@Test fun refusesABadToken()`.

Nothing touches the network. `FakeServer` in `ApiClientTest.kt` is an OkHttp `Interceptor` that answers from a queue
(`server.reply(status, body, type, headers)`) and records `requests` and `bodies`; build the client with
`ApiClient.defaultHttpClient().newBuilder().addInterceptor(server).build()`. Assert the method, path, query and body of
every call you add: the suite is the contract with the server.

Pure rules (`SessionList`, `Markdown`, `Triage`, `ServerAddress`, `RouteCatalog`) are tested
directly with their inputs, no fakes needed.

## What a change must ship with

- New or changed behaviour in `core/` gets a test in the matching file; a bug fix starts with the failing test.
- A new call also satisfies `RoutesTest.everyCallHasARoute` (unique names) and gets an `ApiClientTest` case.
- UI changes in `app/` have no unit tests; run them on a headset (the `headset` skill) and put a screenshot in the PR.
- A detekt finding in deliberately complex code (a parser) is suppressed at the function with a short reason, not by
  loosening `config/detekt/detekt.yml`.
