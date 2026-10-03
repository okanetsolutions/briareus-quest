# Briareus for Quest

[![CI](https://github.com/okanetsolutions/briareus-quest/actions/workflows/ci.yml/badge.svg)](https://github.com/okanetsolutions/briareus-quest/actions/workflows/ci.yml) [![CodeQL](https://github.com/okanetsolutions/briareus-quest/actions/workflows/codeql.yml/badge.svg)](https://github.com/okanetsolutions/briareus-quest/actions/workflows/codeql.yml) [![Release](https://img.shields.io/github/v/release/okanetsolutions/briareus-quest)](https://github.com/okanetsolutions/briareus-quest/releases/latest) [![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A Meta Quest client for [Briareus](https://github.com/nadinyamaui/briareus), the dashboard for running coding agents against your projects. It runs as ordinary 2D panels in Horizon OS, so Briareus sits beside YouTube, WhatsApp or the browser instead of taking over the headset, and it tells you when a session needs you. Written in Kotlin with Jetpack Compose. It talks to the server's client API (`/api/v1`) with a per-device token and works with any Briareus server you can reach over HTTPS. Requires a Quest 2, 3, 3S or Pro (Horizon OS on Android 12L or later).

## What it does

**Made for the headset**

- Runs as panels you place around you. The main window holds the projects and conversations beside the chosen conversation, as the dashboard does; ⧉ opens any conversation in a panel of its own (opening it again brings that panel back), and the status panel is a narrow window to keep beside a video.
- Notifies you while you are in other apps. A background service keeps `GET /events` open and posts a notification when an agent asks a question, a turn finishes, a review holds findings for you, or a session fails. A conversation you have on screen does not notify.
- Answers from the notification. A question or a finished turn takes one tap on the agent's own options (or Continue), sent as the conversation's next message without opening the app; Reply by voice opens the conversation to record an answer.
- The status panel counts what is waiting, working and ready, lists every conversation waiting for you with the agent's question, its options as buttons and a voice reply, and what is working right now with the tool it is on.
- Writes by voice, with no keyboard: everywhere you write to an agent (the composer, a new session's first message, the status panel's reply, a findings note and an errand's input), 🎙 records with the headset's microphone (AAC), the server transcribes it, and the text is shown in a read-only area to check before sending. Each note is added after the last, and ✕ clears it. On a server that cannot transcribe, the field says what the server is missing.
- Large type and pointer-sized targets for a panel a metre away, in the dashboard's dark palette.

**Conversations**

- Lists the projects and conversations the device token permits, newest first, with each conversation's state (needs your answer, findings to decide, working, ready, failed, closed), provider, branch and age, and a count of what waits for you per project. Orchestrator workers stay under their orchestrator.
- Streams each transcript live (`GET /sessions/{id}/events`), resuming from the last line seen, with the time of each message, agent questions, tool activity collapsed into clusters (expandable) and turn results with their time and cost. Status and workspace setup lines are left out.
- Renders agent replies as Markdown: headings, paragraphs, bullet, numbered and task lists, quotes, code blocks with a copy button, tables, rules, bold, italic, strikethrough, inline code and links. Text is selectable.
- Starts conversations on a chosen project, branch, provider, model and effort, or on the project default, with the review loop on or off.
- Sends follow-ups (into the running turn or the queue, as the server decides), takes back queued messages, attaches files, stops, closes, reopens and deletes sessions, turns the review loop on or off, and opens the pull request or ▶ Run's preview in the browser beside it.
- Shows each project's open pull requests, as the dashboard's Pull requests tab does: checks, assignees, author, branch, labels, stacks and linked issues, filtered by author or label. Each one serves its branch (▶ Run), runs the project's errands (code review, QA, feedback and the rest, with the recommended one highlighted), merges, and opens the conversations already run on it. A pull request opens to its description, checks, reviews, findings, linked issues and commits.
- Decides held review findings (fix, optional, dismiss, and a note) and completes the round into the fix session, as the dashboard's Findings screen does.

**Connection**

- Pairs with a per-device token. The server's route catalog (`GET /api/v1/openapi.json`) is read at pairing and on every launch, and a control whose route the server lacks, or that the token's permission may not call, is hidden, so the app adapts to older and newer servers. A Read-only token can follow everything but write nothing.
- Saves projects, conversations and transcripts on the headset, encrypted, so a window opens on what it last showed and then asks the server only for what changed.
- Reconnects on its own with exponential backoff, honouring `Retry-After`. A revoked or expired token returns the app to pairing.

## Install

1. Put the headset in [developer mode](https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/).
2. Download `Briareus-quest.apk` from the [latest release](https://github.com/okanetsolutions/briareus-quest/releases/latest).
3. Install it with `adb install -r Briareus-quest.apk` (or SideQuest), then find Briareus under **Library → Unknown sources**.

## Build

```sh
./gradlew assembleDebug
```

builds `app/build/outputs/apk/debug/app-debug.apk`; `./gradlew installDebug` installs it on a connected headset. You need JDK 17 or later and the Android SDK (platform 35). See [CONTRIBUTING.md](CONTRIBUTING.md) for building on ARM64 Linux.

## Validation

```sh
./gradlew check -Pwerror
```

detekt runs on both modules, and the core (address and token rules, the API client, route catalog and permissions, models, transcript, event streams, notification rules, Markdown and findings) has no Android code and is exercised by `core/src/test`: origin validation, credential headers, the route and arguments of every call, the route catalog and its permissions, redirect rejection, non-JSON responses, rate limiting, oversized requests, uploads and transcription, event stream parsing and resumption, transcript ordering and deduplication, session states, which changes notify and which stay quiet, runtime selection, Markdown blocks and inline styles, and findings verdicts. HTTP is stubbed through an OkHttp interceptor. The app is linted with warnings as errors. CodeQL, the dependency review and actionlint run in CI as well.

## Project layout

| Path | Contents |
| --- | --- |
| `core/` | The portable core: the `/api/v1` client over OkHttp (one table of the calls the app makes and their routes), server-sent events, the route catalog, models, transcript, the notification rules (`Attention.kt`), Markdown and findings. No Android code. |
| `app/` | The Android app: the Keystore vault and encrypted response cache, the store shared by every window, the events service and notifications with direct reply, voice notes, the three windows, and the Compose screens under `ui/`. |

## Pairing

1. Sign in to the web dashboard and open **Settings → Devices and clients**.
2. Create a token: give the headset a name, choose the projects it may see, **Read only** or **Manage**, and an expiry. **Manage** is what lets you answer agents and start sessions from the headset.
3. In the app, enter the public HTTPS server address (or its `/api/v1` URL) and the one-time token.

Behind Cloudflare Access, the server's `/api/v1` and `/api/v1/*` paths need the Bypass application described in the server's [client API guide](https://github.com/nadinyamaui/briareus/blob/main/docs/api-v1.md#deploying-behind-cloudflare-access); the app refuses the login page it is otherwise redirected to.

**Forget this connection** erases the token and everything saved on the headset, and the key that sealed them. It does not revoke the server token or stop running agents; **Revoke the token and forget** also revokes it (`DELETE /token`).

## Security and privacy

- HTTPS is required, against the system's certificate authorities only. The client has no cookies or HTTP cache, refuses all redirects, and never automatically retries a write. TLS 1.2 or later.
- No shared secret is built into the app. The token is sealed with AES-256-GCM under a key generated inside the Android Keystore that never leaves the headset, and is sent only to the server it was paired with.
- Saved responses are sealed with the same key in the app's no-backup directory and erased when the connection is forgotten, revoked or replaced; entries untouched for 30 days are dropped. Nothing is included in backups or device transfers.
- Voice notes are sent to your server for transcription and nowhere else.
- No analytics or telemetry.

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and send a change, and [SECURITY.md](SECURITY.md) for reporting a vulnerability. Every pull request is built, tested and linted by the CI workflow, and every pull request merged into `main` is published as a release with the next minor version. Licensed under the [MIT License](LICENSE).
