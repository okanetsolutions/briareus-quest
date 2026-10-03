---
name: stack
description: The conventions of this codebase: Kotlin and Jetpack Compose panels in an immersive Meta Spatial SDK space on Horizon OS, the portable core with no Android code, the Store as the seam, every server call named in one Routes table and gated by the route catalog, and the comment and line style. Use before writing or reviewing any code under core/ or app/.
---

# This project's stack and conventions

**Kotlin 2.2, Jetpack Compose (Material 3), minSdk 32, targetSdk 34, JDK 17 bytecode.** Horizon OS on Quest 2, 3, 3S
and Pro is Android 12L to 14; targeting 34 is deliberate and lint's `OldTargetApi` is disabled for that reason. There
is one immersive activity on the Meta Spatial SDK (`MainActivity`, an `AppSystemActivity`): every screen is a Compose
panel entity floating around the user, over passthrough or a skybox. Where the panels are is `SpaceLayout` in the core
(pure, tested); `Navigator.space` holds it and the activity only draws it and reads back a panel moved by hand. Meta's
Gradle plugin is deliberately not applied (it serves the Spatial Editor and hot reload, and collects usage data).

**Two modules, one seam.**

- `core/` is plain Kotlin on the JVM: OkHttp, kotlinx-serialization JSON, coroutines. No `android.*` import, ever.
  Everything that can be a rule lives here so it is tested without a device.
- `app/` is Android. `Store` (one per process, `context.store`) owns the connection, the project and session state flows,
  the `GET /events` stream and the per-conversation streams; screens read its flows and call its methods. The
  `EventsService` uses the same `Store` while the app is in the background.

**Server calls.** `Routes.all` is the single table of what the app calls (`ApiRoute(name, method, path, ...)`).
`ApiClient.call("name", args(...))` sends one by name; `Store.mutate` wraps a write and turns failures into a user
message. The server's `GET /openapi.json` is read at pairing and on every launch into a `RouteCatalog`;
`store.can("name")` is true only when the server has that route and the token's permission (`read` or `manage`)
reaches it. Every button or menu item that writes is gated by it. See the `api-call` skill for adding one.

**Security defaults, in `ApiClient.defaultHttpClient()` and `Vault`.** HTTPS only (TLS 1.2+), system CAs only, no
cookies, no cache, `followRedirects(false)`, no retry on connection failure, the token sealed with AES-GCM under a
Keystore key and only ever sent to the paired origin. Do not open any of these, and do not add a network destination.
The one other destination is OpenAI's Realtime API, which `RealtimeCall` opens only while the user holds a voice
conversation, under the user's own key; see the security rule in `AGENTS.md`.

**Dependencies.** Add one only when the platform has nothing that does the job; the UI uses Compose and the standard
library and the core uses OkHttp and serialization. Everything is in `gradle/libs.versions.toml`.

## Style

- 4-space indent, LF, final newline, no trailing spaces (`.editorconfig`, checked in CI). Lines up to 200 characters are
  fine and common; detekt enforces the ceiling. Statements joined on one line with `;` inside a short block are house
  style (`fun flush() { if (x) a(); b() }`).
- KDoc on a class says what it is for and the one or two rules it embodies; inline comments say why, in one line.
  Match the density of the file you are in.
- Compose: one `@Composable` per screen or panel, named like a type; `remember` and `StateFlow.collectAsState()`;
  colours from `Theme.kt`'s palette (`p.ink`, `p.muted`, `p.raise`, `p.warn`, ...), never literals. Type is large and
  targets are pointer-sized because the panel is a metre away.
- Errors: `ApiError(kind)` in the core, with a sentence in `message`; screens show `store.messages`. Read paths retry
  with exponential backoff honouring `Retry-After`; a write is never sent twice.
- Match the dashboard's wording and behaviour (and the Windows client's) when the same thing exists there.
