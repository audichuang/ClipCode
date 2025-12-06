# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**ClipCode** is an IntelliJ Platform plugin for copying file contents with smart formatting, perfect for sharing code with AI assistants.

- **Repository**: https://github.com/audichuang/ClipCode
- **Plugin ID**: `com.github.audichuang.clipcode`
- **Author**: audichuang (audiapplication880208@gmail.com)

## Common Development Commands

### Build and Run
```bash
# Build the plugin
./gradlew build

# Run IntelliJ IDEA with the plugin installed for testing
./gradlew runIde

# Build plugin zip for distribution
./gradlew buildPlugin
# Output: build/distributions/ClipCode-{version}.zip

# Verify plugin compatibility with different IDE versions
./gradlew verifyPlugin

# Clean build artifacts
./gradlew clean
```

## Release Management (Automated via GitHub Actions)

### How Releases Work
When a version tag (e.g., `v1.0.1`) is pushed to GitHub, the `.github/workflows/release.yml` workflow automatically:
1. Builds the plugin
2. Extracts release notes from `plugin.xml` `<change-notes>` section
3. Creates a GitHub Release with the zip file attached
4. Publishes to JetBrains Marketplace (requires PUBLISH_TOKEN secret)
5. Users can download from https://github.com/audichuang/ClipCode/releases

### Step-by-Step Release Process

**Step 1: Update version in plugin.xml (SINGLE SOURCE OF TRUTH)**

Edit `src/main/resources/META-INF/plugin.xml`:
```xml
<version>1.0.1</version>

<change-notes><![CDATA[
<h2>Version 1.0.1</h2>
<ul>
  <li>New feature A</li>
  <li>Bug fix B</li>
  <li>Improvement C</li>
</ul>
]]>
</change-notes>
```

**Step 2: Update version in gradle.properties**

Edit `gradle.properties`:
```properties
pluginVersion=1.0.1
```

**Step 3: Commit and push to main**
```bash
git add -A
git commit -m "Release v1.0.1 - Brief description"
git push origin main
```

**Step 4: Create and push version tag**
```bash
git tag v1.0.1
git push origin v1.0.1
```

**Step 5: Verify release**
- Check GitHub Actions: https://github.com/audichuang/ClipCode/actions
- Check Releases page: https://github.com/audichuang/ClipCode/releases

### Important Notes
- Version must be updated in TWO places: `plugin.xml` and `gradle.properties`
- The `<change-notes>` content in plugin.xml becomes the GitHub Release description
- Tag format must be `v{version}` (e.g., `v1.0.1`, `v2.0.0`)
- Always test with `./gradlew build` before releasing

## Architecture Overview

### Plugin Structure
This is an IntelliJ Platform plugin built with Kotlin and Gradle, targeting IntelliJ 2024.3 - 2025.2. The plugin follows IntelliJ's action-based architecture.

### Core Components

**Actions** (`src/main/kotlin/com/github/audichuang/clipcode/`)
- `CopyFileContentAction.kt`: Main copy engine - handles file/directory content copying with filtering, statistics, and customizable headers. Other actions delegate to this via `performCopyFilesContent()`.
- `CopyAllOpenTabsAction.kt`: Thin wrapper that collects open editor tabs and delegates to CopyFileContentAction
- `CopyGitFilesContentAction.kt`: Copies files from Git staging area, commit UI, changes view, and Git Log with change type labels ([NEW], [MODIFIED], [DELETED], [MOVED]). Uses CommitWorkflowUi API for staging area support.
- `PasteAndRestoreFilesAction.kt`: Reverse operation - parses clipboard content and restores files to project. Handles cross-platform paths and Git change labels.
- `ExternalLibraryHandler.kt`: Handles JAR/ZIP files and decompiled .class files using IntelliJ's LoadTextUtil and PSI APIs

**Settings System**
- `CopyFileContentSettings.kt`: Persistent state component (project-level, stored in `.idea/CopyFileContentSettings.xml`)
- `CopyFileContentConfigurable.kt`: UI configuration panel in IDE settings
- Settings include: header format (`$FILE_PATH` placeholder), pre/post text, file limits, path/pattern filters (include/exclude), notifications

**Plugin Configuration**
- `plugin.xml`: Plugin descriptor with action registrations in multiple groups (CutCopyPasteGroup, EditorTabPopupMenu, ChangesViewPopupMenu, Vcs.Log.ContextMenu, Vcs.Log.ChangesBrowser.Popup, CopyReferencePopupGroup for Rider)

**CI/CD**
- `.github/workflows/release.yml`: Automated release workflow triggered by version tags

### Key Design Patterns

1. **Delegation Pattern**: CopyAllOpenTabsAction and CopyGitFilesContentAction delegate to CopyFileContentAction.performCopyFilesContent() with optional custom header generators
2. **Project-Level Settings**: Uses IntelliJ's `PersistentStateComponent` for storing configuration per project
3. **Action System**: Integrates with IDE context menus through action groups
4. **Notification System**: Uses IntelliJ's notification API for user feedback with configurable visibility
5. **Filter Evaluation**: Excludes are checked first, then includes. Each rule can be individually enabled/disabled.

### Key Features

1. **Copy Operations**:
   - Copy selected files/folders from project view
   - Copy all open tabs content
   - Copy from Git staging area/changes/log with change type labels
   - Customizable header format with `$FILE_PATH` placeholder
   - Pre/post text wrapping
   - File count and size limits for memory safety

2. **Filter System**:
   - PATH filters: Include/Exclude specific paths using IDE file chooser
   - PATTERN filters: Wildcards and regex for file names
   - Individual enable/disable for each filter rule
   - Master switches for include/exclude filters

3. **Restore Operation** (Paste and Restore Files):
   - Parse clipboard content with file headers
   - Recreate directory structure automatically
   - Strip Git change labels automatically
   - Confirmation dialog before creating files
   - Overwrite protection with user choice
   - Keyboard shortcut: `Ctrl+Shift+Alt+V`

## Branch Strategy

- `main`: Stable release branch, used for creating release tags
- `dev`: Development branch for new features and fixes

## Testing
See `TESTING_GUIDE.md` for comprehensive manual testing procedures covering all plugin features.
