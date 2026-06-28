# VS Code ClipCode Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a packageable VS Code extension that copies and restores files using the same clipboard format as IntelliJ ClipCode.

**Architecture:** The VS Code extension is a TypeScript project in `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode`. Core parser, formatter, path, filter, copy, and restore behavior are plain TypeScript modules covered by Node tests; `extension.ts` is thin VS Code command glue.

**Tech Stack:** VS Code Extension API, TypeScript, Node built-in test runner, `@vscode/vsce` for VSIX packaging.

---

### Task 1: Scaffold Packageable VS Code Extension

**Files:**
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/package.json`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/tsconfig.json`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/.vscodeignore`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/README.md`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/extension.ts`

- [ ] **Step 1: Create npm and TypeScript metadata**

Use a minimal VS Code extension manifest with three commands and native settings. `package.json` defines `compile`, `test`, and `package`.

- [ ] **Step 2: Install dependencies**

Run:

```bash
cd /Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode
npm install
```

Expected: `node_modules` and `package-lock.json` are created.

- [ ] **Step 3: Compile empty extension**

Run:

```bash
npm run compile
```

Expected: TypeScript compiles.

### Task 2: Implement Clipboard Compatibility Core With Tests First

**Files:**
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/clipboardFormat.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/test/clipboardFormat.test.ts`

- [ ] **Step 1: Write failing tests**

Tests cover default/custom/generic headers, inline `// file:`, `file: undefined,`, labels, payload assembly, and skipped-file messages.

- [ ] **Step 2: Verify tests fail**

Run:

```bash
npm test
```

Expected: tests fail because `clipboardFormat.ts` is missing.

- [ ] **Step 3: Implement minimal parser and formatter**

Implement `formatHeader`, `buildPayload`, `parseClipboard`, `extractLeadingLabels`, and `stripLeadingLabels`.

- [ ] **Step 4: Verify tests pass**

Run:

```bash
npm test
```

Expected: clipboard compatibility tests pass.

### Task 3: Implement Settings, Filters, And Workspace Path Resolution

**Files:**
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/settings.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/filterMatcher.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/pathResolver.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/test/filterMatcher.test.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/test/pathResolver.test.ts`

- [ ] **Step 1: Write failing tests**

Tests cover default settings, explicit filter rule type, segment-aware paths, filename-only patterns, Windows path normalization, and restore path rejection for `..` or invalid segments.

- [ ] **Step 2: Verify tests fail**

Run:

```bash
npm test
```

Expected: tests fail because modules are missing.

- [ ] **Step 3: Implement settings, filters, and path helpers**

Keep everything plain TypeScript. Use `path.relative`, `path.resolve`, and segment checks.

- [ ] **Step 4: Verify tests pass**

Run:

```bash
npm test
```

Expected: filter and path tests pass.

### Task 4: Implement Copy And Restore Core

**Files:**
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/copy.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/restore.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/fileSystem.ts`
- Create: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/test/copyRestore.test.ts`

- [ ] **Step 1: Write failing tests**

Tests cover recursive folder copy, skipped empty files, size limit marker, pre/post text, restore create, restore overwrite/skip, and `[DELETED]` delete planning.

- [ ] **Step 2: Verify tests fail**

Run:

```bash
npm test
```

Expected: tests fail because copy/restore modules are missing.

- [ ] **Step 3: Implement copy and restore core**

Use Node filesystem APIs for core tests. VS Code command glue can adapt `vscode.Uri` to file paths.

- [ ] **Step 4: Verify tests pass**

Run:

```bash
npm test
```

Expected: all core tests pass.

### Task 5: Wire VS Code Commands And Package VSIX

**Files:**
- Modify: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/src/extension.ts`
- Modify: `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode/README.md`

- [ ] **Step 1: Register commands**

Wire `clipcode.copyToClipboard`, `clipcode.copyAllOpenEditors`, and `clipcode.pasteAndRestoreFiles` to the core modules.

- [ ] **Step 2: Compile**

Run:

```bash
npm run compile
```

Expected: extension compiles without TypeScript errors.

- [ ] **Step 3: Run tests**

Run:

```bash
npm test
```

Expected: all tests pass.

- [ ] **Step 4: Package**

Run:

```bash
npm run package
```

Expected: a `.vsix` file is created in `/Users/audi/GoogleDrive/Github/IntellijPlugin/ClipCodeVSCode`.

### Self-Review

- Spec coverage: copy selected files/folders, copy open editors, paste restore, settings, filters, format labels, safe path restore, package command, and VSIX output are covered by tasks.
- Placeholder scan: no deferred implementation or open requirements remain in this plan.
- Type consistency: core modules use the same `ClipCodeSettings`, `FilterRule`, `ParsedEntry`, `CopyFile`, and `RestorePlan` concepts across tasks.
