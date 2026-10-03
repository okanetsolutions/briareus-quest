# Working on Briareus for Quest

A Meta Quest client for [Briareus](https://github.com/nadinyamaui/briareus): Kotlin, Jetpack Compose, 2D panels on
Horizon OS, talking to a server's `/api/v1` with a per-device token. The README says what the app does; this page says
how the code is arranged and what a change must respect. The skills under `.claude/skills/` go deeper per topic.

## Build and check

```sh
./gradlew check -Pwerror          # core tests, detekt, app lint; the CI gate
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug            # onto a headset in developer mode (see the headset skill)
```

JDK 17 or later and the Android SDK (platform 35, build-tools 35) are required. `-Pwerror` turns Kotlin warnings into
errors; lint and detekt fail on any finding; editorconfig-checker and actionlint run in CI too. A pull request must
pass all of it, and every pull request merged into `main` is released automatically (the release skill).

## Layout

| Path | Contents |
| --- | --- |
| `core/` | Portable Kotlin, **no Android**: `ApiClient` (the `/api/v1` client over OkHttp), `Routes` (the one table of the calls the app makes and their routes), `RouteCatalog` and `Permission` (what the server has and the token may call), `Models`, `Sse`, `SessionList`, `AttentionTracker` (which changes notify), `Markdown`, `Triage`. Tested on the JVM in `core/src/test`. |
| `app/` | The Android app: `Vault` (Keystore-sealed token) and `ResponseCache`, `Store` (the connection and everything read through it, shared by every window and the service), `Conversation` (one transcript stream), `EventsService` and `Notifier` (background notifications with direct reply, `ReplyReceiver`), `VoiceRecorder`, `Windows` and `Activities` (the three panels), and the Compose screens under `ui/`. |
| `config/detekt/` | The detekt configuration; `.editorconfig` holds the formatting rules. |
| `.github/` | CI (build, test, lint, formatting, workflow lint), CodeQL, dependency graph and review, the release workflow, Dependabot, templates. |

## The rules that shape every change

- **The core stays free of Android, the UI free of protocol details.** `Store` is the seam. New server behaviour goes in
  `core/` with a test; a screen reads `Store` state flows and calls `Store` methods.
- **Every server call is a row in `Routes.all`** and is made by name through `ApiClient.call(name, arguments)`. A control
  whose route the server lacks, or whose access the token's permission does not reach, is hidden behind
  `store.can("name")`, so the app works against older, newer and read-only servers. Never hard-code a path in `app/`.
- **Security is not negotiable:** HTTPS only, system certificate authorities only, no cookies, no HTTP cache, no redirects,
  no automatic retry of a write, the token sealed in the Keystore and sent to the paired server alone. Nothing leaves
  the headset except to that server. No analytics.
- **Add a dependency only when the platform has nothing that does the job.** Versions live in
  `gradle/libs.versions.toml`; Dependabot proposes updates monthly.
- **Match the dashboard.** Labels, colours, type and behaviour follow the web app and the Windows client where they
  overlap. When the server changes a route, the web dashboard shows the intended behaviour.
- **Think in panels.** Every window has to work beside YouTube or a chat, at a metre or more, with a pointer instead of a
  finger: large type, pointer-sized targets, nothing that depends on a touch gesture.

## Style

- Follow the file you are in: 4-space indent, wide lines are fine (detekt's ceiling is 200 characters), `{ if (x) a; b }`
  on one line is house style.
- Comments say *why*, briefly; KDoc on a class or function says what it is for in one or two sentences. Do not narrate
  obvious code.
- Prefer the standard library and coroutines over new abstractions. One class per concern, one file per class or
  closely related group.
- Errors reach the user as a sentence (`ApiError.message`), not as a stack trace; read failures are retried with backoff
  by the screen or the service, writes never are.

## Checking a change

- Core behaviour needs a test in `core/src/test` (JUnit 4, `runTest`, HTTP stubbed through the `FakeServer` interceptor
  in `ApiClientTest.kt`). The tests pin the route and arguments of every call: changing a route means changing its test
  on purpose.
- UI changes are checked on a headset against a Briareus server; a screenshot goes in the pull request.
- Before pushing: `./gradlew check -Pwerror`, and the formatting rules in `.editorconfig` (LF, final newline, no
  trailing spaces).
