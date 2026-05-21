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

# [ClipCode] recent context, 2026-05-21 11:18pm GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (7,504t read) | 907,866t work | 99% savings

### Apr 24, 2026
S282 審查 ClipCode 插件的增量修正與測試覆蓋率，確認是否符合發佈標準。 (Apr 24 at 6:07 AM)
S274 審查 IntelliJ 外掛重構後的 Gradle 配置、程式碼邏輯等價性與測試覆蓋率 (Apr 24 at 6:07 AM)
S292 追蹤並驗證 Git DELETED 檔案的端對端還原流程，確認舊版 Bug 是否已修復。 (Apr 24 at 6:40 AM)
S293 為 ClipCode 專案建立完整的重構驗收與測試補強提示詞，確保優化過程不破壞現有功能。 (Apr 24 at 8:52 AM)
S301 驗證 GitContentResolver 與 RestoreExecutor 的重構邏輯及測試覆蓋率，並確認 Gradle 建置環境穩定性。 (Apr 24 at 9:18 AM)
### Apr 26, 2026
14089 10:04a 🔵 核心 SDK 模組中未發現 ProjectFileIndex
14093 " 🔵 SDK 核心 JAR 檔案定位搜尋
14094 " 🔵 特定版本 SDK JAR 檔案定位
14095 " 🔵 本地專案目錄 JAR 檔案清單確認
14096 " 🔵 成功定位 SDK 核心 JAR 檔案
14097 " 🔵 SDK 測試與工具模組清單確認
14098 " 🔵 API 類別定位結果：intellij.platform.core.jar
14099 " 🔵 API 類別定位結果：intellij.platform.core.impl.jar
14101 " 🔵 SDK 核心目錄 JAR 檔案規模確認
14102 " 🔵 ProjectFileIndex 類別定位程序啟動
14616 10:20a 🔵 多模組專案路徑解析邏輯檢視
14621 " 🔄 強化多模組專案的路徑解析與標籤機制
14624 " 🔵 ClipboardPathResolver 程式碼結構確認
14615 " 🔵 確認 RTK (Rust Token Killer) 工具配置
14618 " 🔵 專案狀態與檔案變更確認
14620 " 🔄 大幅重構 ClipboardPathResolver 與測試覆蓋
14623 " 🔵 分析 IntelliJ Plugin 檔案選取邏輯
14626 " 🔵 分析 IntelliJ Plugin 設定介面與過濾規則邏輯
14627 " 🔵 分析 PathRuleMatcher 路徑比對邏輯
14628 " 🔄 重構 ClipboardPathResolver 以支援多模組路徑標籤
14630 " 🔄 整合 PathRuleMatcher 與 ClipboardPathResolver 邏輯
14632 " 🔵 確認 PathRuleMatcher 實作細節
14629 " 🔵 確認 PathRuleMatcher 的路徑比對邊界條件
14631 " 🔵 確認 CopyFileContentAction 的檔案處理與過濾邏輯
14634 " 🔵 確認多模組專案路徑解析與過濾機制
14636 " 🔵 分析 ClipboardPathResolver 的路徑解析機制
14638 " 🔵 深入分析 ClipboardPathResolver 的路徑解析與正規化實作
14637 " 🔵 ClipboardPathResolver 完整實作審查
14639 " 🔵 CopyFileContentAction 整合路徑解析與過濾邏輯
14640 " 🔵 CopyFileContentConfigurable UI 整合路徑解析
14642 " 🔵 分析 CopyPathFormatter 的路徑格式化邏輯
14660 10:21a 🔵 分析 CopyFileContentConfigurable 的設定儲存與規則解析邏輯
14697 10:22a 🔵 ClipboardPathResolverTest 測試覆蓋率確認
15463 1:08p 🔴 修復潛在的邊緣案例錯誤
### May 7, 2026
54461 11:27a 🔵 觀察到大型程式碼區塊在傳輸過程中被截斷
54462 " 🔵 RTK (Rust Token Killer) 工具說明與規範
54463 " 🔵 ClipCode 外掛程式碼架構與限制機制分析
54464 " 🔵 批次處理服務檔案大小統計
54465 " 🔵 ClipCode 檔案處理邏輯與 OOM 防護機制
54466 " 🔵 ClipCode 目錄遞迴處理與二進位檔案過濾機制
54467 " 🔵 批次處理服務錯誤處理與重試機制分析
54469 " 🔵 ClipCode 設定資料結構分析
54530 11:32a 🔵 設定檔持久化機制與執行環境確認
### May 16, 2026
55503 7:25a 🔵 Agent learned 'using-superpowers' skill
55504 " 🔵 Agent learned about RTK (Rust Token Killer) CLI proxy
55505 12:05p 🔵 Discovered file extraction logic in GitSelectionCollector
55519 8:21p 🔵 RTK (Rust Token Killer) CLI tool documentation retrieved
55520 " 🔵 ClipCode plugin file structure identified
55518 8:22p 🔵 Skill file not found during initial setup
55521 " 🔵 Superpowers skill documentation retrieved

Access 908k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>