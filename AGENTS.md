# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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


<claude-mem-context>
# Memory Context

# [ClipCode] recent context, 2026-04-24 8:56am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (5,671t read) | 686,085t work | 99% savings

### Apr 24, 2026
7979 6:04a 🔵 備份原始 ChangeTypeLabel 程式碼
7982 " 🔵 盤點重構後的程式碼結構
7985 " 🔵 分析 CopyGitFilesContentAction 原始實作邏輯
7987 " 🔄 Gradle 建置環境升級與配置更新
7990 " 🔵 確認重構後 CopyGitFilesContentAction 的精簡狀態
7992 " 🔵 確認 build.gradle.kts 檔案狀態
7993 " 🔵 確認 settings.gradle.kts 配置
8002 " 🔵 CopyGitFilesContentAction 程式碼審查
S282 審查 ClipCode 插件的增量修正與測試覆蓋率，確認是否符合發佈標準。 (Apr 24 at 6:07 AM)
S274 審查 IntelliJ 外掛重構後的 Gradle 配置、程式碼邏輯等價性與測試覆蓋率 (Apr 24 at 6:07 AM)
8080 6:11a 🔵 IJGP 2.x 遷移關鍵技術債盤點
8086 " 🔵 專案結構重構與遷移進度確認
8088 " 🔵 專案技術債與配置狀態盤點
8090 " 🔵 專案檔案結構確認
8107 6:12a 🔵 RestoreExecutor 測試邏輯分析
8109 " 🔵 GitClipboardFormatter 測試覆蓋範圍分析
8112 " 🔵 RestorePlanBuilder 邏輯架構分析
8115 " 🔵 RestorePlanBuilder 測試覆蓋範圍分析
8119 " 🔵 Gradle 依賴版本配置分析
8122 " 🔵 專案建置屬性與相容性配置盤點
8125 " 🔵 核心模組 Git 狀態確認
8129 " 🔵 核心模組重構分析：PasteAndRestoreFilesAction 與其依賴
8132 " 🔵 PasteAndRestoreFilesAction 重構前狀態分析
8146 6:13a 🔵 重構前剪貼簿解析邏輯分析
8152 " 🔵 建置產出物與插件描述檔分析
8155 " 🔵 Gradle 插件發佈與驗證任務清單分析
8157 " 🔵 IntelliJ Platform Gradle Plugin 版本歷史盤點
8163 " 🔵 Gradle publishPlugin 任務執行狀態確認
8164 " 🔵 Gradle signPlugin 任務執行狀態確認
8166 " 🔵 Gradle buildPlugin 任務執行狀態確認
8369 6:37a 🔄 ClipCode 插件架構與發布流程優化
8373 6:38a ✅ ClipCode 專案大規模重構與功能擴充
8375 " 🔄 PasteAndRestoreFilesAction 執行緒模型重構
8377 " 🔵 RestoreExecutor 模組化實作
8378 " 🔄 RestoreExecutor 檔案 IO 操作實作
8381 " 🔄 ClipboardPathResolver 路徑解析邏輯強化
8382 " ✅ CI/CD 發布工作流更新
8384 " 🔵 CI/CD 工作流：plugin.xml 多路徑解析策略
8388 6:39a 🔵 GitSelectionCollector 變更擷取邏輯
8391 " 🔵 GitSelectionCollector 狀態解析機制
S292 追蹤並驗證 Git DELETED 檔案的端對端還原流程，確認舊版 Bug 是否已修復。 (Apr 24 at 6:40 AM)
8775 8:48a 🔵 Git 版本控制與刪除紀錄一致性檢查
8782 8:49a 🔵 驗證 ClipboardPathResolverTest 測試覆蓋率
8783 " 🔵 驗證 ClipboardRestoreParserTest 測試覆蓋率
8784 " 🔵 驗證 RestoreExecutorTest 測試覆蓋率
8785 " 🔵 驗證 RestorePlanBuilderTest 測試覆蓋率
8786 " 🔵 驗證 ChangeTypeLabelTest 測試邏輯
8787 " 🔵 驗證 GitClipboardFormatterTest 格式化邏輯
8788 " 🔵 ClipboardPathResolverTest 測試邏輯深度分析
8789 " 🔵 ClipboardRestoreParserTest 測試邏輯深度分析
8790 " 🔵 RestoreExecutorTest 測試邏輯深度分析
8791 " 🔵 RestorePlanBuilderTest 測試邏輯深度分析
8823 8:53a 🔄 程式碼優化與嚴謹性測試流程建立
S293 為 ClipCode 專案建立完整的重構驗收與測試補強提示詞，確保優化過程不破壞現有功能。 (Apr 24 at 8:54 AM)
**Investigated**: 分析了 ClipCode 專案的架構，特別是 `CopyGitFilesContentAction` 與 `PasteAndRestoreFilesAction` 的拆分狀態，以及針對 Git DELETED 檔案處理的三層修復機制。

**Learned**: 確認了專案目前的核心風險點在於重構後的行為回歸，特別是 VFS 操作、Git 標籤解析以及執行緒切換的正確性，並明確了 12 項亟需補齊的測試盲點。

**Completed**: 產出了詳盡的 Codex 提示詞（Prompt），涵蓋了角色定義、專案背景、重構驗收標準、測試補齊清單及禁止事項，並提供了驗證指令集。

**Next Steps**: 將提示詞交付給 Codex 執行，並準備在乾淨的開發環境（如建立 worktree）中進行程式碼審查與測試補齊，確保所有 Gradle 驗證指令通過。


Access 686k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>