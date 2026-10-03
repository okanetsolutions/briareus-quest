# Contributing

Thanks for helping with Briareus for Quest. This page covers how to build, test and send a change.

## Building

The app is Kotlin, with Jetpack Compose for the UI. You need JDK 17 or later and the Android SDK (platform 35); Android Studio brings both. Then:

```sh
./gradlew assembleDebug
```

builds `app/build/outputs/apk/debug/app-debug.apk`. To install it on a headset in developer mode, connected over USB or Wi-Fi:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On an ARM64 Linux machine (Google ships the SDK's native tools for x86-64 only), use ARM64 builds of `aapt2` and `adb` and point Gradle at that `aapt2` in `~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/path/to/aapt2
```

## Testing

```sh
./gradlew check -Pwerror
```

runs the core tests, detekt on both modules and the app's lint. Everything under `core/` (address and token rules, the `/api/v1` client, route catalog, models, transcript, event streams, notification rules, Markdown, findings) has no Android code and is covered by `core/src/test`, with HTTP stubbed through an OkHttp interceptor. New core behaviour needs a test there. UI changes in `app/` are checked by running the app on a headset against a Briareus server; a screenshot in the pull request helps.

The same checks run in CI for every pull request, and every one must pass:

- **Warnings are errors.** `-Pwerror` makes Kotlin warnings errors, and lint fails on any warning.
- **Static analysis.** detekt runs on both modules with `config/detekt/detekt.yml`, which only relaxes what this codebase does on purpose (wide lines, Compose naming); a finding in deliberately complex code is suppressed at the function with a reason, not by loosening the configuration.
- **Formatting.** Files follow `.editorconfig` (LF, final newline, no trailing spaces), checked by editorconfig-checker. `.gitattributes` keeps text LF in every checkout, Windows included.
- **Workflows and dependencies.** actionlint checks `.github/workflows`; CodeQL analyses the Kotlin; a pull request that adds a dependency with a known high-severity vulnerability is refused by the dependency review.

## Layout

| Path | Contents |
| --- | --- |
| `core/` | Portable logic, no Android: the API client, models, notification rules, Markdown. |
| `app/` | The Android app: the encrypted vault and cache, the store, the events service and notifications, voice notes, and the Compose screens under `ui/`. |

[AGENTS.md](AGENTS.md) describes the architecture and the rules a change must respect in more detail, for people and for coding agents alike; `.claude/skills/` holds topic guides (conventions, testing, adding a server call, running on a headset, releases) that Claude Code loads on demand and that read fine on their own.

## Style

- Keep the core free of Android and the UI free of protocol details; the store is the seam between them.
- Follow the file you are in: 4-space indent, short comments that say why.
- Add a dependency only when the platform has nothing that does the job.
- Match the dashboard: labels, colours, type and behaviour follow the web app and the Windows client where they overlap.
- Think in panels in a space: every panel floats a metre or two away and is used with a pointer or by voice.

## Sending a change

1. Fork and branch from `main`.
2. Make the change with its tests.
3. Open a pull request describing what changed and why, and how you checked it. CI must pass before it is merged.

Every pull request merged into `main` is released: the release workflow tags the next minor version, builds the APK with that version and publishes it. Pushing a `v*` tag by hand still publishes that tag as it is.

## License

Briareus for Quest is released under the [MIT License](LICENSE). By sending a pull request you agree that your contribution is licensed under the same terms.
