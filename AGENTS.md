# AGENTS.md — ClipCode

Single source of truth for AI agents in this repo. `CLAUDE.md` imports this file
— edit here only.

**ClipCode** — IntelliJ Platform plugin (Kotlin) that copies file/folder contents
with smart formatting for sharing code with AI assistants, and restores files
back from that clipboard text. Plugin id `com.github.audichuang.clipcode`.

## Clipboard format — the Kotlin side of a cross-tool contract

The format itself, its byte-for-byte invariants, and the fixture-regeneration flow
are **shared** with the sibling VS Code port (Snipcode) and live in the work-root
`AGENTS.md`. Read that before changing anything about the wire format. From a lone
clone of this repo, the executable copy of the contract is
`src/test/resources/clipboard-contract.json` + `ContractFixturesTest`.

This side's implementation:

| Piece | Where |
|---|---|
| Label set + label regexes | `ChangeTypeLabel.kt` |
| Canonical, test-pinned assembler | `ClipboardPayloadFormatter.kt` — byte-mirror of `clipboardFormat.ts buildPayloadInternal` |
| Header + label joining | `GitClipboardFormatter.buildHeader` — shared by the formatter and by `CopyGitFilesContentAction` |
| Parse + `escapeContent`/`unescapeContent`/`joinContent` | `ClipboardRestoreParser.kt` |

**Caveat:** the regular explorer / open-tabs copy path does **not** go through the
formatter — it assembles the same wire rules inline in
`CopyFileContentAction.buildCopyPayload` with its own `headerFormat.replace`.
Changing a header or label rule means touching the formatter chain **and** that
inline site.

Kotlin-side pins for the shared invariants:

- Kotlin regex `\s` is already ASCII, so the parser/formatter need no explicit class
  (the TS side pins an `ASCII_WS` class to match this behaviour). `TokenEstimator.kt`
  spells the ASCII set out anyway — same semantics, but it keeps the two token
  regexes visibly identical to the eye.
- `$FILE_PATH` substitution uses `String.replace` (literal, not regex).
- Parsing splits on `splitLines` — a `\r?\n` regex, **never** `String.lines()`.
- The copy notification's token count is a cross-tool contract too — see the
  work-root `AGENTS.md`. Every path that puts a payload on the clipboard must report
  it via `CopyFileContentAction.showPayloadNotification`, so size colouring is
  identical in both tools.

## Build / run

    ./gradlew build        # compile + verify, includes the JUnit suite
    ./gradlew runIde       # launch a sandbox IDE with the plugin installed
    ./gradlew buildPlugin  # → build/distributions/ClipCode-<version>.zip

**Platform range:** `pluginSinceBuild = 252` (IntelliJ **2025.2+**);
`pluginUntilBuild` is empty (no upper bound). See `gradle.properties` — do not
assume older 2024.x builds.

`./gradlew build` runs the suite under `src/test/kotlin/`, so a red test is a
`BUILD FAILED`. Judge pass/fail by the `BUILD SUCCESSFUL` / `N failed` text. Pure
logic should have unit tests; UI and git4idea-runtime paths that can't run headless
are verified manually via `./gradlew runIde`, scripted in `TESTING_GUIDE.md` (which
states its own scope).

## Release (tag-triggered → JetBrains Marketplace)

Releases come off `main` (feature work on `dev`). Pushing a `v<version>` tag runs
`.github/workflows/release.yml`: build → **signPlugin** (certificate secrets) →
GitHub Release → publish to JetBrains Marketplace. Two non-obvious rules:

- **The version is `pluginVersion` in `gradle.properties` only.** `build.gradle.kts`
  injects it (`version = properties("pluginVersion")`) and `patchPluginXml` writes
  it into the built plugin.xml — `src/main/resources/META-INF/plugin.xml` has no
  `<version>` of its own, so don't try to bump one there.
- Release notes are the hand-written HTML in the `changeNotes = """…"""` block of
  `build.gradle.kts` (there is no `CHANGELOG.md`). `patchPluginXml` bakes it into
  the built plugin.xml and the workflow extracts it into the GitHub Release body,
  so add a new `<h2>Version X</h2>` block there before tagging. Tag must be `v<version>`.

## Where to start in the code

Most copy paths route through `CopyFileContentAction.performCopyFilesContent()`
(`CopyAllOpenTabsAction` delegates to it). `CopyGitFilesContentAction` is dual:
all-VFS with no deleted markers → `performCopyFilesContent`; mixed/revision/deleted
→ `GitClipboardPayloadBuilder`. Git label mapping: `GitContentResolver` /
`GitSelectionCollector`. Path & filter: `ClipboardPathResolver` + `PathRuleMatcher`.
Restore: `PasteAndRestoreFilesAction` → `ClipboardRestoreParser` → `RestoreExecutor`.
For full structure read `src/main/kotlin/com/github/audichuang/clipcode/` — don't
trust a hand-written tree.

PR panel (Tool Window, base...HEAD three-dot diff + copy + origin behind-check):
`ClipCodePrToolWindowFactory` / `ClipCodePrPanel` + `BranchDiffProvider` (git4idea diff +
ahead/behind); copy reuses `GitClipboardPayloadBuilder`.

## Permissions

Settings are project-level (`.idea/CopyFileContentSettings.xml`). Ask before
pushing, creating tags/releases, or editing the release workflow.
