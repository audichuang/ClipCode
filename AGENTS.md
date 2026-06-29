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

Format authority on this side: `ChangeTypeLabel.kt` (label set + regexes),
`GitClipboardFormatter.kt` (build), `ClipboardRestoreParser.kt` (parse + the
`escapeContent`/`unescapeContent`/`joinContent` helpers). The VS Code mirror is
`ClipCodeVSCode/src/clipboardFormat.ts`. **Change the label set, bracket syntax,
header rule, or escape marker on one side → update the other, or cross-tool restore
silently breaks.** Round-trip is covered by `ClipCodeVSCode` unit + e2e tests; the
Kotlin side has no test harness, so verify its mirror by hand against those.

## Build / run

    ./gradlew build        # compile + verify
    ./gradlew runIde       # launch a sandbox IDE with the plugin installed
    ./gradlew buildPlugin  # → build/distributions/ClipCode-<version>.zip

Targets IntelliJ 2024.3–2025.2. There are no unit tests — testing is manual per
`TESTING_GUIDE.md`.

## Release (tag-triggered → JetBrains Marketplace)

Releases come off `main` (feature work on `dev`). Pushing a `v<version>` tag runs
`.github/workflows/release.yml`: build → GitHub Release → publish to JetBrains
Marketplace (needs the `PUBLISH_TOKEN` secret). Two non-obvious rules:

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
(`CopyAllOpenTabsAction` / `CopyGitFilesContentAction` delegate to it). Git label
mapping: `GitContentResolver` / `GitSelectionCollector`. Path & filter logic:
`ClipboardPathResolver` + `PathRuleMatcher`. Restore: `PasteAndRestoreFilesAction`
→ `ClipboardRestoreParser` → `RestoreExecutor`. For full structure read
`src/main/kotlin/com/github/audichuang/clipcode/` — don't trust a hand-written tree.

## Permissions

Settings are project-level (`.idea/CopyFileContentSettings.xml`). Ask before
pushing, creating tags/releases, or editing the release workflow.
