# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common Development Commands

### Build and Run
```bash
# Build the plugin
./gradlew build

# Run IntelliJ IDEA with the plugin installed for testing
./gradlew runIde

# Run UI tests  
./gradlew runIdeForUiTests

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

**Settings System**
- `CopyFileContentSettings.kt`: Persistent state component for project-level settings (stored in `.idea/CopyFileContentSettings.xml`)
- `CopyFileContentConfigurable.kt`: UI configuration panel in IDE settings
- Settings include: header format, pre/post text, file limits, extension filters, notifications

**Plugin Configuration**
- `plugin.xml`: Plugin descriptor with action registrations, extension points, and metadata
- Actions are registered in multiple groups for compatibility with different IDEs (general IntelliJ, Rider)

### Key Design Patterns

1. **Project-Level Settings**: Uses IntelliJ's `PersistentStateComponent` for storing configuration per project
2. **Action System**: Integrates with IDE context menus through action groups
3. **Notification System**: Uses IntelliJ's notification API for user feedback with configurable visibility

### Version Management
- Version is maintained in two places (must be synchronized):
  - `gradle.properties`: `pluginVersion` property
  - `plugin.xml`: `<version>` tag
- Change notes must be updated in `plugin.xml` before release
- The `update_version.sh` script automates version synchronization and release workflow