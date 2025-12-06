# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common Development Commands

### Build and Run
```bash
# Build the plugin
./gradlew build

# Run IntelliJ IDEA with the plugin installed for testing
./gradlew runIde

# Verify plugin compatibility with different IDE versions
./gradlew verifyPlugin

# Clean build artifacts
./gradlew clean
```

### Release Management
```bash
# Create a new version and release (interactive script)
./scripts/update_version.sh

# The script will:
# 1. Prompt to confirm change notes were updated in plugin.xml
# 2. Allow specifying new version (or auto-increment)
# 3. Update version in plugin.xml and gradle.properties
# 4. Build the project
# 5. Create git tag
# 6. Optionally push to origin

# Manual build for distribution
./gradlew buildPlugin
# Output: build/distributions/CodeSnap-{version}.zip
```

## Architecture Overview

### Plugin Structure
This is an IntelliJ Platform plugin built with Kotlin and Gradle, targeting IntelliJ 2024.3 - 2025.2. The plugin follows IntelliJ's action-based architecture.

### Core Components

**Actions** (`src/main/kotlin/com/github/audichuang/codesnap/`)
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

### Version Management
- Version is maintained in two places (must be synchronized):
  - `gradle.properties`: `pluginVersion` property
  - `plugin.xml`: `<version>` tag
- Change notes must be updated in `plugin.xml` before release
- The `update_version.sh` script automates version synchronization and release workflow

### Testing
See `TESTING_GUIDE.md` for comprehensive manual testing procedures covering all plugin features.
