# VS Code ClipCode Port Design

## Decision

Build a new VS Code extension project named `ClipCodeVSCode` as an MVP that can be installed and used in VS Code. The first version prioritizes exact clipboard format compatibility with the existing IntelliJ ClipCode plugin over deep Git UI integration.

Approved scope: Option A, MVP interoperability.

## Goal

Create a VS Code extension that can copy files into the same clipboard format produced by IntelliJ ClipCode and restore files from clipboard text produced by either IDE.

## Non-Goals For MVP

- No VS Code Git Changes or Git Log selection integration in the first version.
- No shared runtime package consumed by both IntelliJ and VS Code in the first version.
- No webview settings UI. VS Code native settings are enough.
- No binary/external library decompilation equivalent. VS Code has no direct equivalent to IntelliJ PSI decompilation.

## New Project Location

Create the new project next to the IntelliJ plugin repository:

```text
/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode
```

The IntelliJ repository remains the behavioral reference. The new VS Code repository owns the TypeScript implementation and tests.

## Compatibility Contract

The clipboard text format is treated as a product contract shared by IntelliJ and VS Code.

Default file header:

```text
// file: $FILE_PATH
```

Example payload:

```text
// file: src/main.ts
export const answer = 42;

// file: [DELETED] src/old.ts
// This file has been deleted in this change
```

Required parser and formatter behavior:

- `$FILE_PATH` is the only header placeholder.
- Custom headers are supported by replacing `$FILE_PATH`.
- Generic fallback headers are supported: `// file:`, `# file:`, `/* file: ... */`, and bare `file:` when the path looks like a real path.
- Headers only match when the whole line matches.
- Inline source text containing `// file:` must not split files.
- JavaScript object properties such as `file: undefined,` must not split files.
- Change labels are parsed only at the start of the header path.
- Supported labels are `[NEW]`, `[MODIFIED]`, `[DELETED]`, and `[MOVED]`.
- `[DELETED]` entries restore as delete operations.
- `[NEW]`, `[MODIFIED]`, and `[MOVED]` entries restore as write operations.
- Clipboard paths always use `/`.
- Restore rejects paths with `..` or invalid Windows filename characters in any segment.
- Restore never writes outside the selected VS Code workspace root.

Known IntelliJ behavior to preserve for MVP:

- Header and content are joined with `\n`.
- Optional pre-text and post-text wrap the payload.
- Optional extra blank line between files is supported.
- Oversized copied files produce a header plus `// File skipped: size exceeds limit (...)`.

## VS Code Commands

The extension contributes these commands:

- `clipcode.copyToClipboard`
  - Available from Explorer context menus for files and folders.
  - Recursively copies selected folders.
  - Applies filters, file count limit, file size limit, binary skip, and duplicate path skip.

- `clipcode.copyAllOpenEditors`
  - Copies all open text editors with workspace-relative paths.
  - Skips untitled documents because they have no stable restore path.

- `clipcode.pasteAndRestoreFiles`
  - Reads clipboard text.
  - Parses compatible ClipCode headers.
  - Builds a restore plan.
  - Shows a confirmation summary.
  - Creates missing directories, writes files, overwrites only after confirmation, and deletes `[DELETED]` targets.

## VS Code Settings

Use native VS Code configuration under the `clipcode` namespace:

- `clipcode.headerFormat`
  - Default: `// file: $FILE_PATH`

- `clipcode.preText`
  - Default: empty string

- `clipcode.postText`
  - Default: empty string

- `clipcode.addExtraLineBetweenFiles`
  - Default: true

- `clipcode.setMaxFileCount`
  - Default: true

- `clipcode.fileCountLimit`
  - Default: 30

- `clipcode.maxFileSizeKB`
  - Default: 500

- `clipcode.showCopyNotification`
  - Default: true

- `clipcode.useFilters`
  - Default: false

- `clipcode.useIncludeFilters`
  - Default: true

- `clipcode.useExcludeFilters`
  - Default: true

- `clipcode.filterRules`
  - Default: empty array
  - Item shape:

```json
{
  "type": "PATH",
  "action": "INCLUDE",
  "value": "src",
  "enabled": true
}
```

Supported `type` values: `PATH`, `PATTERN`.
Supported `action` values: `INCLUDE`, `EXCLUDE`.

Unlike the current IntelliJ settings UI, VS Code filter rules must store `type` explicitly and must not infer type from the value.

## Core TypeScript Modules

Keep the implementation boring and small.

```text
src/extension.ts
src/settings.ts
src/clipboardFormat.ts
src/pathResolver.ts
src/filterMatcher.ts
src/copy.ts
src/restore.ts
src/fileSystem.ts
```

Responsibilities:

- `extension.ts`
  - Registers commands and passes VS Code inputs into core modules.

- `settings.ts`
  - Reads and validates VS Code settings.
  - Supplies defaults matching IntelliJ.

- `clipboardFormat.ts`
  - Owns header formatting, parsing, change labels, and payload assembly.
  - Contains the strongest IntelliJ compatibility tests.

- `pathResolver.ts`
  - Converts workspace files to clipboard paths.
  - Resolves clipboard paths back into workspace targets.
  - Rejects unsafe paths.

- `filterMatcher.ts`
  - Implements segment-aware path rules and filename pattern rules.

- `copy.ts`
  - Expands selected files/folders.
  - Applies limits and filters.
  - Reads text files and assembles clipboard payload.

- `restore.ts`
  - Builds restore plans and executes create, overwrite, skip, and delete operations.

- `fileSystem.ts`
  - Thin wrappers around `vscode.workspace.fs` and `TextDecoder`.
  - Centralizes filesystem behavior for tests.

## User Flow

Copy selected file:

```text
Explorer selection
  -> copy command
  -> collect URIs
  -> expand folders
  -> filter and limit files
  -> format payload
  -> vscode.env.clipboard.writeText()
```

Restore:

```text
clipboard text
  -> parse entries
  -> resolve targets under workspace root
  -> show summary
  -> ask overwrite choice when needed
  -> write/delete through vscode.workspace.fs
```

## Error Handling

- No workspace folder: show error and stop.
- Empty clipboard: show warning and stop.
- No parseable entries: show warning and stop.
- Unsafe or ambiguous path: skip and include it in the restore summary.
- Existing file during restore: ask whether to overwrite all, skip existing, or cancel.
- Individual write/delete failures: continue remaining operations and report failures at the end.

## Testing Strategy

Use TypeScript unit tests for core compatibility. MVP verification focuses on core behavior rather than VS Code UI glue.

Required tests:

- Header formatting matches IntelliJ examples.
- Custom header `$FILE_PATH` parsing.
- Generic fallback header parsing.
- Inline `// file:` source text is not split.
- `file: undefined,` is not treated as a header.
- Multiple leading labels are parsed.
- `[DELETED]` becomes delete operation.
- Windows-style paths normalize to `/` when copied.
- Restore rejects `..`.
- Segment-aware path filters do not match partial sibling names.
- Pattern filters match filenames only.
- Copy payload includes pre/post text and extra blank lines like IntelliJ.
- Empty files are skipped in MVP to match current IntelliJ behavior.

Manual smoke checks:

1. Package VS Code extension as VSIX.
2. Install it in VS Code.
3. Copy a file in VS Code, paste/restore it in IntelliJ.
4. Copy a file in IntelliJ, paste/restore it in VS Code.
5. Confirm `[DELETED]` copied from IntelliJ deletes the target in VS Code after confirmation.

## Release Shape

The first deliverable is a local VSIX package built from `ClipCodeVSCode`.

Required commands:

```bash
npm test
npm run compile
npm run package
```

`npm run package` produces a `.vsix` file suitable for VS Code install-from-file.

## Risks

- IntelliJ currently trims parsed content on restore. VS Code MVP matches that behavior to preserve compatibility.
- VS Code multi-root workspaces can be ambiguous. MVP resolves only inside the selected/current workspace root and skips unsafe paths.
- Git UI integration is intentionally postponed. The format supports labels now, so Git copy can be added in a later phase without changing restore compatibility.
