# Quest and Windows feature review

Compared with Windows commit `a20e7a9` and server commit `c8203b8`; updated through 2026-10-05.
References: [Windows feature list](https://github.com/okanetsolutions/briareus-windows/blob/a20e7a92203498c43bc07cc17b89c1811b1219cc/README.md),
[Windows PR implementation](https://github.com/okanetsolutions/briareus-windows/blob/a20e7a92203498c43bc07cc17b89c1811b1219cc/app/screen_pulls.c),
and [server API contract](https://github.com/nadinyamaui/briareus/blob/c8203b8596c88ee038fcce0301a6c5776c3be5f3/docs/api-v1.md).

## Current behavior

| Area | Quest implementation |
| --- | --- |
| Native panels | Kotlin/Compose Android activities displayed as Horizon OS panels; PRs, files, reviews and issues render natively |
| Separate panels | Conversations, PRs, tabs, issues and both lists have independent document panels; voice supports `own_panel` |
| Navigation | Recognized PR/issue links open native views; unsupported links remain selectable text; no external browser actions |
| PR files | Windows-style collapsible directory tree on the left and selected patch on the right, with independent scrolling, old/new line numbers, wrapping and selectable text |
| Large/changing diffs | Lazy diff rows and explicit pagination pinned to head/base SHA; unavailable patches and truncated lists are identified |
| Code review | A button on the board and above each PR tab starts the project's configured reviewer on the current PR branch and opens its conversation; voice has `start_code_review` |
| Review results | Reviews displays verdicts and findings; findings decisions remain in conversation triage |
| Checks | A compact summary in Overview; no Checks tab or individual check-run rows; old checks links open Overview |
| Issues | Project issue list/detail with labels, label filter, parent context, linked PRs and a Work on issue form |
| Labels/tags | PR labels and filtering, plus issue labels and filtering |
| Run | Store-owned startup, immediate progress panel, setup transcript and embedded preview; known serving sessions are reused and different previews have independent panels |
| Run timeout | 170 seconds, matching Windows; the override covers both call and read timeouts, without write retries |
| Voice scope | Select one project before starting a call; prompts, tool plans, session reads and screen context remain in that project |
| Voice actions | Requested actions execute directly; required input and ambiguous targets still need clarification; voice merging and the status tool are removed |
| Status and notifications | Status panel, session alerts, direct replies, alert settings and background event service removed; upgrades clear old alert channels and notifications |
| Project administration | Not included, by user preference; the app lists and selects existing projects |

The native application uses an embedded Android WebView for the app served by Run, just as Windows embeds WebView2.
The preview's existing Access handling remains in place. No dependencies or network destinations were added.

Live events run while a panel is shown or a voice call is active. Android's required quiet microphone foreground-service
notification remains during a call; it is separate from the removed session-alert feature. See
[Android's notification requirement](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

Panel launches request adjacent placement. Horizon OS controls the physical position and distance of the current 2D
activities; see [Meta's panel launch documentation](https://developers.meta.com/vr/documentation/spatial-sdk/hybrid-apps-overview/#launching-adjacent-panel-activities).

## Remaining gaps

1. Full review bodies and inline comment threads anchored to diff lines, with findings decisions directly on the PR.
2. Run profiles, default-branch previews and stop/restart controls; voice currently opens existing previews but has no dedicated Run-start tool.
3. Paginated issue timelines, nested epic/sub-issue trees, issue types and Projects v2 fields.
4. A panel picker and explicit selection when several PRs are visible; voice has no tool for selecting a particular file in the diff.
5. Richer PR stack navigation and reviewer filtering.

Project administration, the status panel, session notifications and voice merging are intentionally excluded.

## Validation

- 72 core tests cover API request arguments/timeouts, project boundaries, native navigation, removed voice tools,
  checks-link fallback, review startup, file trees, unified diff numbering and revision-pinned pagination.
- `./gradlew check -Pwerror`, debug/release builds, editorconfig-checker and actionlint are the pre-push checks.
- Headset checks verified saved pairing and live reads, independent PR/issue document tasks, document reuse,
  issue detail/list views and the existing Run preview loading successfully without duplicate startup.
- Project-specific voice selection and screen-context filtering were checked with the microphone off.
- The status/notification removal was installed and verified: obsolete components, permissions and alert channels were
  absent, the main sidebar rendered without its status shortcut, Settings opened and live data still loaded.
- Native screenshots are kept in local QA artifacts rather than publishing private project content in the repository.
- The latest Checks-summary update is built but not installed because the headset went offline; its visual check is pending.
- Windows-style file selection/wrapping still needs a completed headset interaction check; earlier attempts were blocked
  by headset sleep or Horizon's Guardian dialog. Live microphone recognition, revision-changing pagination and
  older/read-only server combinations have not been exercised on the headset.

## Remaining headset QA

1. Install the latest APK without erasing the paired connection.
2. Verify Overview has only the checks summary and the tab bar has Overview, Files, Reviews and Commits.
3. Select several files in Files; verify the tree stays on the left, the patch changes and wrapping/scrolling work.
4. Open PRs and issues in separate panels by voice and verify project scoping with multiple projects visible.
5. Exercise pagination across a changed PR revision and verify mixed revisions are refused.
6. Exercise unavailable-route and read-only tokens, and capture the latest panels at a comfortable reading distance.
