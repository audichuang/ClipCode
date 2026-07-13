# AGENTS.md — ClipCode

Single source of truth for AI agents (Codex, Claude Code, Gemini) in this repo.
`CLAUDE.md` imports this file — edit here only.

**ClipCode** — IntelliJ Platform plugin (Kotlin) that copies file/folder contents
with smart formatting for sharing code with AI assistants, and restores files
back from that clipboard text. Plugin id `com.github.audichuang.clipcode`.

## Sibling: ClipCodeVSCode (shared clipboard format — keep compatible)

ClipCodeVSCode (VS Code extension, displayName "Snipcode") is the VS Code port of
this plugin. **The clipboard text format is a cross-tool contract** — files copied
in one tool restore in the other. Both sides must agree on:

- a per-file header built from a `headerFormat` with a `$FILE_PATH` placeholder
- change labels `[NEW] [MODIFIED] [DELETED] [MOVED]` prefixed onto the path
- pre/post text wrapping + the blank-line-between-files option
- the `//clipcode-esc: ` escape prefix: a content line that itself parses as a
  header is escaped with this marker on copy and unescaped on paste, so files that
  contain a literal `// file: …` line round-trip instead of splitting. The marker
  string MUST be byte-identical on both sides.
- the optional leading `// clipcode-root: <name>` metadata line (source root
  basename, emitted for single-root projects only): Paste & Restore uses it to
  align folder levels deterministically (`RestoreBase.kt` / VS Code
  `restoreBase.ts`); parsers drop it before the first header so it never becomes
  a file. Also byte-identical on both sides.

Format authority on this side: `ChangeTypeLabel.kt` (label set + regexes),
`ClipboardPayloadFormatter.kt` (the **canonical / test-pinned** assembler —
byte-mirror of `clipboardFormat.ts buildPayloadInternal`; used by git/PR paths
via `GitClipboardPayloadBuilder` and by golden fixtures),
`ClipboardRestoreParser.kt` (parse + `escapeContent`/`unescapeContent`/`joinContent`).
**Caveat:** regular explorer/open-tabs copy still assembles the same wire rules
**inline** in `CopyFileContentAction.buildCopyPayload` — do not assume every
copy path goes through the formatter. The VS Code mirror is
`ClipCodeVSCode/src/clipboardFormat.ts` (single assembler on that side).
**Change the label set, bracket syntax, header rule, or escape marker on one
side → update the other (and both IntelliJ assembly sites if needed), or
cross-tool restore silently breaks.**

Strict-alignment invariants (both sides must match byte-for-byte):
- Header regexes use an **ASCII** whitespace class only (`[ \t\n\x0B\f\r]`), not
  Unicode `\s`. Kotlin regex `\s` is already ASCII; the TS side pins an explicit
  `ASCII_WS` class so a full-width-space-indented line isn't a header on one side
  and content on the other.
- `$FILE_PATH` substitution is **literal** — Kotlin `String.replace`, TS
  `split('$FILE_PATH').join(...)` (never `replaceAll(str, str)`, which expands `$&`
  etc.). A path containing `$` must round-trip verbatim.
- Line splitting on parse is `\r?\n` only (never a lone `\r`): Kotlin uses
  `splitLines` (a `\r?\n` regex), NOT `String.lines()`.
- Known accepted residual: `trim()`/`isBlank()` treat U+001C–U+001F and U+FEFF
  differently across the two stdlibs. Only triggers when a structural line is
  entirely such control/BOM chars, which real payloads never contain — left as-is.

**Cross-tool contract is pinned by golden fixtures**, not by hand: the same
`clipboard-contract.json` is committed byte-identically in
`src/test/resources/` (here) and `ClipCodeVSCode/test/fixtures/`. `ContractFixturesTest`
(here) and `test/contract.test.ts` (VS Code) both assert their build + parse match
those frozen bytes, plus a SHA guard so the two copies can't drift. Regenerate via
`ClipCodeVSCode/scripts/gen-contract-fixtures.cjs` (VS Code is the format authority),
copy the JSON to both repos, and update `EXPECTED_FIXTURES_SHA` on both sides.

## Build / run

    ./gradlew build        # compile + verify
    ./gradlew runIde       # launch a sandbox IDE with the plugin installed
    ./gradlew buildPlugin  # → build/distributions/ClipCode-<version>.zip

**Platform range:** `pluginSinceBuild = 252` (IntelliJ **2025.2+**); 
`pluginUntilBuild` is empty (no upper bound). See `gradle.properties` — do not
assume older 2024.x builds.

There is a JUnit suite under `src/test/kotlin/` run by the `test` task, so
`./gradlew build` includes it — `BUILD FAILED` on a red test. Judge pass/fail by
the `BUILD SUCCESSFUL` / `N failed` text, not a piped exit code (`| tail`/`| grep`
swallow gradle's real exit code). Pure logic should have unit tests; UI /
git4idea-runtime paths that can't run headless are still verified manually via
`./gradlew runIde`. `TESTING_GUIDE.md` covers core copy/git/restore only — not the
PR panel — and its sample zip version may lag `pluginVersion`.

## Release (tag-triggered → JetBrains Marketplace)

Releases come off `main` (feature work on `dev`). Pushing a `v<version>` tag runs
`.github/workflows/release.yml`: build → **signPlugin** (certificate secrets) →
GitHub Release → publish to JetBrains Marketplace (`PUBLISH_TOKEN` plus
`CERTIFICATE_CHAIN` / `PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD`). Two non-obvious rules:

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
