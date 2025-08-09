# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common Development Commands

### Build and Run
```bash
# Build the plugin
./gradlew build

# Run IntelliJ IDEA with the plugin installed for testing
./gradlew runIde

# Run performance tests
./gradlew testIdePerformance

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
# Output: build/distributions/Copy_File_Content-{version}.zip
```

## Architecture Overview

### Plugin Structure
This is an IntelliJ Platform plugin built with Kotlin and Gradle. The plugin follows IntelliJ's action-based architecture.

### Core Components

**Actions** (`src/main/kotlin/com/github/mwguerra/copyfilecontent/`)
- `CopyFileContentAction.kt`: Main action for copying file/directory content from project view context menu
- `CopyAllOpenTabsAction.kt`: Action for copying content from all open editor tabs
- `PasteAndRestoreFilesAction.kt`: Action for parsing clipboard content and restoring files to the project (reverse operation)

**Settings System**
- `CopyFileContentSettings.kt`: Persistent state component for project-level settings (stored in `.idea/CopyFileContentSettings.xml`)
- `CopyFileContentConfigurable.kt`: UI configuration panel in IDE settings
- Settings include: header format, pre/post text, file limits, path/pattern filters (include/exclude), notifications

**Plugin Configuration**
- `plugin.xml`: Plugin descriptor with action registrations, extension points, and metadata
- Actions are registered in multiple groups for compatibility with different IDEs (general IntelliJ, Rider)

### Key Design Patterns

1. **Project-Level Settings**: Uses IntelliJ's `PersistentStateComponent` for storing configuration per project
2. **Action System**: Integrates with IDE context menus through action groups
3. **Notification System**: Uses IntelliJ's notification API for user feedback with configurable visibility
4. **Bidirectional Operations**: Supports both copying files to clipboard and restoring files from clipboard content

### Key Features

1. **Copy Operations**:
   - Copy selected files/folders from project view
   - Copy all open tabs content
   - Customizable header format with file paths
   - Pre/post text wrapping
   - File count limits for memory safety

2. **Filter System**:
   - Include/Exclude paths using IDE file chooser
   - Pattern matching for file names (wildcards and regex)
   - Individual enable/disable for each filter rule
   - Master switches for include/exclude filters

3. **Restore Operation** (Paste and Restore Files):
   - Parse clipboard content with file headers
   - Recreate directory structure automatically
   - Confirmation dialog before creating files
   - Overwrite protection with user choice
   - Keyboard shortcut: `Ctrl+Shift+Alt+V`

### Version Management
- Version is maintained in two places (must be synchronized):
  - `gradle.properties`: `pluginVersion` property
  - `plugin.xml`: `<version>` tag
- Change notes must be updated in `plugin.xml` before release
- The `update_version.sh` script automates version synchronization and release workflow