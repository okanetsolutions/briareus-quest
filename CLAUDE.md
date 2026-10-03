# Claude Code instructions for Briareus for Quest

Architecture, layout, the rules every change must respect and how to check one are in @AGENTS.md. Read it first.

## Skills

Load the matching skill under `.claude/skills/` before working in its area, not after getting stuck:

- `stack`: the conventions of this codebase and the core/app seam.
- `testing`: running and writing the core tests, detekt, lint and formatting; the local toolchain.
- `api-call`: adding or changing a server call, end to end from `Routes` to a screen.
- `headset`: building, installing and debugging on a Quest over adb.
- `release`: how a merge becomes a release, versions and signing.

## Guardrails

- Never commit, push, tag or open a pull request unless the user asks for it in this conversation. Writing code is not
  permission to commit it. Work on a branch off `main`; never commit to `main` directly, since every push to `main`
  publishes a release.
- Run `./gradlew check -Pwerror` before saying a change is done. Report failures with their output; do not weaken a
  test, a lint rule or the detekt configuration to get past them without saying so.
- Do not add a dependency, a permission in the manifest, or a network destination without calling it out.
- Keep the core free of Android imports and the screens free of URLs and routes.
- Do not change the strings the server contract pins (route names in `Routes`, event and field names in `Models`)
  without checking the server's `docs/api-v1.md`.

## Writing

Commit messages and pull request descriptions say what changed and why in plain sentences, like the existing history.
Comments and KDoc follow the file's voice: short, saying why.
