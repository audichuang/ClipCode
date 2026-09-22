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
  spells the ASCII set out anyway — same semantics, but it keeps the two scans
  visibly identical to the eye.
- **`RegexOption.IGNORE_CASE` is banned in the parser.** It is
  `CASE_INSENSITIVE|UNICODE_CASE`, which folds the Turkish dotless `ı` (U+0131) onto
  `i` where JavaScript's `/i` does not — `// fıle: x.ts` was a header here and content
  there. `GENERIC_FILE_HEADER` spells the token out as `[Ff][Ii][Ll][Ee]:`; the label
  regexes are case-sensitive on both sides.
- The builder emits `ClipboardRestoreParser.POST_TEXT_MARKER` before a non-empty post
  text and the parser stops there. Do NOT reintroduce a `postText` parameter on `parse()`
  — see the work-root `AGENTS.md` for why reconstructing the footer from the receiver's
  setting silently deletes real content.
- No `String.trim()` / `isBlank()` in the parse path — `ClipboardRestoreParser.asciiTrim`
  only, including in `RestorePlan.isPlaceholderBody` and `ChangeTypeLabel.stripLabels`.
- The per-file filter decision lives in `CopyFilterMatcher` and **nowhere else**.
  `GitClipboardPayloadBuilder` applies it and the file-count limit too: it is where this
  action lands whenever a selection mixes revision or deleted entries, and it used to
  apply neither, so an EXCLUDE rule stopped working the moment a staged file was selected
  beside the excluded one. Directory pruning may only run when the INCLUDE set is
  PATH-only — a PATTERN include says nothing about which directories can hold a match.
  Only entries that really carry content count as copied and spend the limit: the
  `// Unable to read file content` / `// Error reading file content` placeholders travel in
  `content`, not in `skippedReason`, so they used to do both. Shared with the VS Code side —
  see the work-root `AGENTS.md` before changing either half.
- `$FILE_PATH` substitution uses `String.replace` (literal, not regex).
- Path-shape regexes never use `.` — `[\s\S]` for "any character". Java's `.` skips five
  line terminators (U+0085 included) and JavaScript's four, while a lone `\r` survives the
  `\r?\n` header split; with `.` and `matches()`, `D:/a\rb.txt` was not absolute here and
  was in VS Code, so one payload restored a file in one tool only. Applies to
  `ClipboardPathResolver` `WINDOWS_STYLE_PATH` / `WINDOWS_ABSOLUTE_PATH`, `CopyPathFormatter`
  and `PathRuleMatcher`; TS mirror `pathResolver.ts isWindowsStylePath`.
- Parsing splits on `splitLines` — a `\r?\n` regex, **never** `String.lines()`.
- The copy notification's four statistics (characters / lines / words / tokens) are
  a cross-tool contract too — see the work-root `AGENTS.md`. They are derived from
  the payload string by `TokenEstimator.stats` and **nowhere else**: never re-add
  per-file counters to `CopySession`, which is how `Total characters` came to exclude
  headers and blank separator lines. Every path that puts a payload on the clipboard
  must report it via `CopyFileContentAction.showPayloadNotification`, so both the
  numbers and the size colouring are identical in both tools.

## Build / run

    ./gradlew build        # compile + verify, includes the JUnit suite
    ./gradlew runIde       # launch a sandbox IDE with the plugin installed
    ./gradlew buildPlugin  # → build/distributions/ClipCode-<version>.zip

**Platform range:** `pluginSinceBuild = 252` (IntelliJ **2025.2+**);
`pluginUntilBuild` is empty (no upper bound). See `gradle.properties` — do not
assume older 2024.x builds.

`./gradlew build` runs the suite under `src/test/kotlin/`, so a red test is a
`BUILD FAILED`. Judge pass/fail by the `BUILD SUCCESSFUL` / `N failed` text.
**Running it locally is the only thing that ever runs these tests** — `release.yml`
is this repo's only workflow and it runs `buildPlugin signPlugin`, never `build`,
so a tag push says nothing about the suite. Pure logic should have unit tests; UI
and git4idea-runtime paths that can't run headless are verified manually via
`./gradlew runIde`, scripted in `TESTING_GUIDE.md` (which states its own scope).

## Release (tag-triggered → GitHub Release zip)

Releases come off `main` (feature work on `dev`). **Users install the
`ClipCode-<version>.zip` attached to the GitHub Release by hand — this plugin is
not delivered through the JetBrains Marketplace.** A release is therefore finished
when that zip is attached to the Release, **not** when the workflow goes green.

**Run `scripts/release.sh <version>`.** It preflights (on `main`, clean tree, in
sync with origin, `pluginVersion` matches, a `<h2>Version X</h2>` changeNotes block
exists, tag unused), tags, pushes, waits for the workflow, then checks the zip is
really attached. It prints one line — `OK v1.2.9 live: <url>` or `FAIL: <reason>` —
and exits 0 only when live, so nobody has to remember the verification step or read
a log. Run `./gradlew build` yourself first: CI never runs the test suite.

Pushing a `v<version>` tag runs `.github/workflows/release.yml`: build → signPlugin
→ GitHub Release. The repo has no Actions secrets at all, so `signPlugin` is SKIPPED
and the unsigned zip is what ships — expected, not a regression.

**The JetBrains Marketplace publish step is commented out** (owner decision,
2026-09-15): there is no `PUBLISH_TOKEN`, so it failed on every release v1.0.2…v1.2.8
and made a red run meaningless. From v1.2.9 on **a red Release run is a real failure**
— don't wave it through. Don't re-enable that step or add the secrets unless the owner
raises it first.

Two non-obvious rules:

- **The version is `pluginVersion` in `gradle.properties` only.** `build.gradle.kts`
  injects it (`version = properties("pluginVersion")`) and `patchPluginXml` writes
  it into the built plugin.xml — `src/main/resources/META-INF/plugin.xml` has no
  `<version>` of its own, so don't try to bump one there.
- Release notes are the hand-written HTML in the `changeNotes = """…"""` block of
  `build.gradle.kts` (there is no `CHANGELOG.md`). `patchPluginXml` bakes it into
  the built plugin.xml and the workflow extracts it into the GitHub Release body,
  so add a new `<h2>Version X</h2>` block there before tagging. Tag must be `v<version>`.
  The block is **cumulative** — every past version stays in the file — but the workflow
  now slices out only the `<h2>Version <pluginVersion></h2>` section for the Release body
  (before 2026-09-15 it pasted all 20 of them onto every Release page, which is why they
  all looked identical). So the new heading's version must match `pluginVersion`
  **exactly**, or the workflow logs `WARNING: no '<h2>Version X' block found` and falls
  back to dumping the whole history again.

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
