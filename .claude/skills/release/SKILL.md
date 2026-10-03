---
name: release
description: How a change becomes a release of Briareus for Quest: every push to main is tagged with the next minor version and published with the APK, the versionCode formula, the optional signing secrets, releasing a tag by hand, and what to check before merging. Use when asked about versions, releases, signing, or why a release did not appear.
---

# Releases

`.github/workflows/release.yml` runs on every push to `main` (each merged pull request) and on any `v*` tag:

1. On a push to `main` it finds the latest `v*` tag, bumps the **minor** version (`v1.4.0 → v1.5.0`), creates the tag
   with the GitHub Actions bot and pushes it. A pushed tag is released as it is, so a patch release is
   `git tag -a v1.4.1 -m "Briareus for Quest 1.4.1" && git push origin v1.4.1`.
2. It builds with `-PversionName=<x.y.z> -PversionCode=<x*10000 + y*100 + z>` (see `app/build.gradle.kts`), running the
   core tests first.
3. With the repository secrets `BRIAREUS_KEYSTORE_BASE64`, `BRIAREUS_KEYSTORE_PASSWORD`, `BRIAREUS_KEY_ALIAS` and
   `BRIAREUS_KEY_PASSWORD` it builds and signs the **release** APK (minified, shrunk, `proguard-rules.pro`), so each
   release installs over the previous one. Without them it publishes the **debug** APK, whose key is fresh on every
   runner, so users must uninstall before installing the next.
4. `softprops/action-gh-release` creates the GitHub release named `Briareus for Quest vX.Y.Z` with generated notes and
   `Briareus-quest.apk` attached. The README's install link points at the latest release.

Because of step 1, **nothing lands on `main` that is not ready to ship**. The CI workflow gates every pull request
(build, tests, detekt, lint, formatting, workflow lint, CodeQL, dependency review); do not merge around a red check.

## Setting up signing once

```sh
keytool -genkeypair -v -keystore briareus-release.jks -alias briareus -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 briareus-release.jks   # → BRIAREUS_KEYSTORE_BASE64
```

Add the four secrets under the repository's Settings → Secrets and variables → Actions. Keep the keystore out of the
repo (`*.jks` is ignored); losing it means users must uninstall to upgrade.

## Checking a release

The run's summary lists the core test count. On the headset, `adb shell dumpsys package com.okanetsolutions.briareus.quest | grep version`
shows the installed `versionName`/`versionCode`. A release that did not appear is usually the workflow's tag push being
refused by a branch or tag protection rule, or the concurrency group `release` still running the previous one.
