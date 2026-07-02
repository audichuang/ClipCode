# ClipCode PR 面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ClipCode 加一個 Tool Window「ClipCode PR」,可選 base ref 看 `base..HEAD` 的變更檔、一鍵用現有格式複製,並檢查 remote 提醒分支是否落後 origin。

**Architecture:** 純本地 git(git4idea),不接 GitHub API。新面板複用既有的 `GitContentResolver`(內容解析)與抽出的 payload builder(格式化),資料流:選 base → `BranchDiffProvider` 算 `base..HEAD` 的 `List<Change>` + ahead/behind → 構造既有的 `GitSelectionCollector.Selection(source = GIT_LOG_OR_HISTORY)` → `GitContentResolver.resolve()` → 顯示/複製。

**Tech Stack:** Kotlin、IntelliJ Platform SDK、git4idea、Swing(Tool Window UI)。

## Global Constraints

- 相容 IntelliJ 2024.3–2025.2(見 `build.gradle.kts` / `gradle.properties`)。
- **無單元測試框架**(AGENTS.md)。每個 task 的驗收 = `./gradlew build` 編譯通過 + `./gradlew runIde` 手動驗證指定情境。不寫 pytest/JUnit 式測試。
- **剪貼簿格式是跨工具契約**(ClipCodeVSCode)。PR 面板一律複用既有格式邏輯,不得改動 header 規則、change label、escape marker。
- 版本號只在 `gradle.properties` 的 `pluginVersion`;本計畫不動版本。
- git4idea API 簽名以編譯為準:計畫給主方案 + fallback,若主方案簽名對不上,採 fallback(直接跑 git command)。
- 分支:在 `dev` 上做(feature 分支)。每個 task 結束 commit,不 push。

## 檔案結構

- `CopyGitFilesContentAction.kt`(改)— 把 payload 建構邏輯外移後改為呼叫共用元件。
- `GitClipboardPayloadBuilder.kt`(新)— 由 `List<ResolvedGitEntry>` + settings 產生剪貼簿文字與 summary。唯一格式化來源,action 與 PR 面板共用。
- `BranchDiffProvider.kt`(新)— git4idea:算 `base..HEAD` 的 `List<Change>`、ahead/behind、觸發 fetch、列出候選 base ref。
- `ClipCodePrPanel.kt`(新)— Swing UI:base 下拉、remote 橫幅、檔案清單、Copy 按鈕。
- `ClipCodePrToolWindowFactory.kt`(新)— ToolWindowFactory。
- `plugin.xml`(改)— 加 `<toolWindow>` extension。

---

### Task 1: 抽出共用的 clipboard payload builder

把 `CopyGitFilesContentAction` 裡把 `List<ResolvedGitEntry>` 組成剪貼簿文字的 private 邏輯外移成獨立元件,讓 PR 面板能複用同一份格式化。純重構,行為不變。

**Files:**
- Create: `src/main/kotlin/com/github/audichuang/clipcode/GitClipboardPayloadBuilder.kt`
- Modify: `src/main/kotlin/com/github/audichuang/clipcode/CopyGitFilesContentAction.kt`(移除 `buildGitClipboardPayload`、`appendContent`、`GitCopyPayload`,改呼叫新元件)

**Interfaces:**
- Produces:
  ```kotlin
  object GitClipboardPayloadBuilder {
      data class Payload(val text: String, val summary: String)
      // 必須在 ReadAction 內呼叫(會讀 VFS bytes);indicator 可為 null(面板情境用簡易 indicator 或傳入既有的)
      fun build(
          contentEntries: List<GitContentResolver.ResolvedGitEntry>,
          deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
          pathResolver: ClipboardPathResolver,
          settings: CopyFileContentSettings?,
          indicator: com.intellij.openapi.progress.ProgressIndicator
      ): Payload
  }
  ```
- Consumes: `GitContentResolver.ResolvedGitEntry`、`ClipboardPathResolver`、`CopyFileContentSettings`、`GitClipboardFormatter`、`ClipboardRestoreParser`(escapeContent)。全部既有。

- [ ] **Step 1: 建 `GitClipboardPayloadBuilder.kt`**

把 `CopyGitFilesContentAction.buildGitClipboardPayload` 與 `appendContent` 的邏輯**逐字搬過去**(含 preText/postText escape、maxFileSize skip、summary 組字串、`logger` 換成 `Logger.getInstance(GitClipboardPayloadBuilder::class.java)`),對外只暴露 `build(...)` 與 `Payload`。`appendContent` 設為 private。保持 `ReadAction.compute` 包住 VFS 讀取那段。

- [ ] **Step 2: 改 `CopyGitFilesContentAction`**

刪掉 `buildGitClipboardPayload`、`appendContent`、`GitCopyPayload`。在 `copyResolvedEntries` 的 `Task.Backgroundable.run` 內改成:
```kotlin
payload = GitClipboardPayloadBuilder.build(
    contentEntries, deletedMarkerEntries, pathResolver, settings, indicator
)
```
`onSuccess` 內 `payload.text` / `payload.summary` 用法不變(型別改為 `GitClipboardPayloadBuilder.Payload?`)。

- [ ] **Step 3: 編譯**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL,無 unresolved reference。

- [ ] **Step 4: 手動驗證無回歸**

Run: `./gradlew runIde`
在 sandbox IDE:改幾個檔(NEW/MODIFIED/DELETED)、開 Commit 或 Changes view 選檔 → 右鍵 **ClipCode: Copy Full Source** → 貼到記事本,確認格式與 Task 1 前**完全一致**(header、`[NEW]/[MODIFIED]` 標籤、檔案內容、summary 通知)。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/audichuang/clipcode/GitClipboardPayloadBuilder.kt src/main/kotlin/com/github/audichuang/clipcode/CopyGitFilesContentAction.kt
git commit -m "refactor: extract GitClipboardPayloadBuilder for reuse"
```

---

### Task 2: BranchDiffProvider(git4idea 比對 + remote 檢查)

提供 PR 面板所需的資料:候選 base ref、`base..HEAD` 的變更、相對 origin 的 ahead/behind、fetch。

**Files:**
- Create: `src/main/kotlin/com/github/audichuang/clipcode/BranchDiffProvider.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class BranchDiffProvider(private val logger: Logger) {
      data class RemoteStatus(
          val ahead: Int,          // 本地領先 origin 幾個 commit
          val behind: Int,         // 本地落後 origin 幾個 commit(> 0 → 提醒 pull)
          val upstream: String?,   // 對應的 origin ref,如 "origin/main";null = 無 upstream
          val fetched: Boolean     // 這次是否成功 fetch(false = 用本地快取,離線/認證失敗)
      )
      // 目前 repo 可選的 base ref(remote 分支優先),供下拉用
      fun candidateBaseRefs(project: Project): List<String>
      // base..HEAD 的變更;base 為 ref 字串(如 "origin/main")
      fun diffChanges(project: Project, baseRef: String): List<com.intellij.openapi.vcs.changes.Change>
      // 相對 origin 的新鮮度;doFetch = true 時先背景 fetch
      fun remoteStatus(project: Project, doFetch: Boolean): RemoteStatus
  }
  ```
- Consumes: git4idea `GitRepositoryManager` / `GitRepository` / `GitChangeUtils` / `GitFetchSupport` / `GitLineHandler`。

實作指引(以編譯為準,主方案優先):
- 取 repo:`git4idea.GitUtil.getRepositoryManager(project).repositories.firstOrNull()`(多 repo 時取第一個;無 repo 回空/預設)。
- `diffChanges`:主方案 `git4idea.changes.GitChangeUtils.getDiff(repository.project, repository.root, baseRef, "HEAD", true)`(回 `Collection<Change>`)。若簽名對不上,fallback 用 `GitChangeUtils.getDiff(project, root, oldRev, newRev, detectRenames)` 對應的實際多載;再不行則跑 `GitLineHandler(GitCommand.DIFF)` 加 `--name-status` 自行解析(最後手段)。
- `remoteStatus`:upstream 由 `repository.currentBranch?.findTrackedBranch(repository)?.nameForRemoteOperations` 或 `GitBranchUtil` 取;ahead/behind 主方案跑 `GitLineHandler(GitCommand.REV_LIST)` 參數 `--count --left-right <upstream>...HEAD`,輸出 `behind<TAB>ahead`(left = upstream 專有 = behind,right = HEAD 專有 = ahead)。無 upstream 回 `ahead=0,behind=0,upstream=null`。
- fetch:`git4idea.fetch.GitFetchSupport.fetchSupport(project).fetch(repository)`;包 try/catch,失敗設 `fetched=false` 後仍用本地快取算 ahead/behind。
- `candidateBaseRefs`:remote 分支名(`repository.branches.remoteBranches.map { it.nameForRemoteOperations }`,即 `origin/main` 這種)排前,current branch 的 upstream 置頂;去重。

- [ ] **Step 1: 建 `BranchDiffProvider.kt`**

依上面 Interfaces + 實作指引寫出三個方法與 `RemoteStatus`。所有 git4idea 呼叫包 try/catch,失敗記 `logger.warn` 並回安全預設(空清單 / `RemoteStatus(0,0,null,false)`),不得丟例外到 UI。

- [ ] **Step 2: 編譯**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。若 git4idea API 簽名報錯,依實作指引的 fallback 調整後再編譯至通過。

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/audichuang/clipcode/BranchDiffProvider.kt
git commit -m "feat: add BranchDiffProvider for base..HEAD diff and remote freshness"
```

---

### Task 3: PR Tool Window(UI + 串接 + Copy)

把 Task 1、2 串成使用者可見的面板。

**Files:**
- Create: `src/main/kotlin/com/github/audichuang/clipcode/ClipCodePrPanel.kt`
- Create: `src/main/kotlin/com/github/audichuang/clipcode/ClipCodePrToolWindowFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`(在 `<extensions defaultExtensionNs="com.intellij">` 內加 toolWindow)

**Interfaces:**
- Consumes: `BranchDiffProvider`(Task 2)、`GitContentResolver` + `GitSelectionCollector.Selection` + `SelectionSource.GIT_LOG_OR_HISTORY`(既有)、`GitClipboardPayloadBuilder`(Task 1)、`ClipboardPathResolver`、`CopyFileContentSettings`。
- Produces: `ClipCodePrToolWindowFactory : ToolWindowFactory`(plugin.xml 參照)。

- [ ] **Step 1: plugin.xml 加 toolWindow**

在 `<extensions defaultExtensionNs="com.intellij">` 內加:
```xml
<toolWindow id="ClipCode PR"
            anchor="right"
            secondary="true"
            icon="AllIcons.Vcs.Branch"
            factoryClass="com.github.audichuang.clipcode.ClipCodePrToolWindowFactory"/>
```

- [ ] **Step 2: 建 `ClipCodePrPanel.kt`**

Swing `JPanel`(BorderLayout),持有 `project`:
- 頂部(NORTH):`JComboBox<String>`(base ref,items = `BranchDiffProvider.candidateBaseRefs`)+ `Refresh` 按鈕。
- remote 橫幅(頂部下方):`JLabel`(或 `EditorNotificationPanel` 風格),依 `RemoteStatus` 顯示:
  - `behind > 0` → 「⚠ origin 有 {behind} 個新 commit,建議 pull」+ `Fetch` 按鈕。
  - `behind == 0 && upstream != null` → 「✓ 與 {upstream} 同步(ahead {ahead})」。
  - `upstream == null` → 「本分支無對應 origin 分支」。
  - `fetched == false` → 附註「(未能連線 remote,以下為本地快取狀態)」。
- 中間(CENTER):`CheckboxTree` 或簡易 `JBList`,列 `base..HEAD` 變更檔,每列顯示 `{label} {clipboardPath}`(label 來自 `ChangeTypeLabel`,path 來自 `ClipboardPathResolver.toClipboardPath`),預設全勾。
- 底部(SOUTH):`Copy to Clipboard` 按鈕(無勾選時 disabled)。

`reload(baseRef, doFetch)`:在 `Task.Backgroundable` 內呼叫 `BranchDiffProvider.diffChanges` + `remoteStatus`,`onSuccess` 更新橫幅與清單。Refresh = `reload(當前 base, doFetch = true)`;切 base 下拉 = `reload(新 base, doFetch = false)`;首次載入 `reload(預設 base, doFetch = true)`。

- [ ] **Step 3: Copy 串接**

Copy 按鈕點擊 → 在 `Task.Backgroundable` 內:
```kotlin
val selection = GitSelectionCollector.Selection(
    changes = checkedChanges,          // 勾選的 Change
    selectedFiles = emptyList(),
    untrackedPaths = emptySet(),
    gitStatusNodes = emptySet(),
    source = SelectionSource.GIT_LOG_OR_HISTORY
)
val entries = GitContentResolver(logger).resolve(project, selection)
val content = entries.filter { it.hasContent }
val deleted = entries.filter { it.changeType == ChangeTypeLabel.DELETED && !it.hasContent }
val payload = ReadAction.compute<GitClipboardPayloadBuilder.Payload, RuntimeException> {
    GitClipboardPayloadBuilder.build(content, deleted, pathResolver, settings, indicator)
}
```
`onSuccess`:`Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(payload.text), null)`,並依 `settings.showCopyNotification` 用 `CopyFileContentAction.showNotification` 顯示 `payload.summary`。空清單時提示「無變更可複製」。

- [ ] **Step 4: 建 `ClipCodePrToolWindowFactory.kt`**

```kotlin
class ClipCodePrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClipCodePrPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 5: 編譯**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 手動驗證全流程**

Run: `./gradlew runIde`
在 sandbox IDE 開一個有 git remote 的專案:
1. 右側出現「ClipCode PR」Tool Window。
2. base 下拉有 `origin/*` 候選;選一個 → 中間列出 `base..HEAD` 變更檔(標籤正確)。
3. remote 橫幅:製造一個落後 origin 的分支 → 顯示 behind + 建議 pull + Fetch;點 Fetch 後數字更新。與 origin 同步 → 顯示同步。離線 → 顯示「未能連線 remote」。
4. 勾選部分檔 → Copy → 貼到記事本,格式與從 commit history 複製**完全一致**(用 ClipCodeVSCode round-trip 對照 NEW/MODIFIED/DELETED/MOVED)。
5. 空 diff → 清單空、Copy 停用或提示無變更。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/audichuang/clipcode/ClipCodePrPanel.kt src/main/kotlin/com/github/audichuang/clipcode/ClipCodePrToolWindowFactory.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: add ClipCode PR tool window with branch-diff copy and remote check"
```

---

## Self-Review

**Spec coverage:**
- 可視化 PR 面板 → Task 3(Tool Window)。✓
- 選 base 看 base..HEAD 變更 → Task 2 `diffChanges` + Task 3 清單。✓
- 用現有格式複製 → Task 1(抽共用 builder)+ Task 3 Copy 串接。✓
- 檢查 remote 提醒落後 → Task 2 `remoteStatus`(fetch + ahead/behind)+ Task 3 橫幅。✓
- 錯誤處理(無 repo/無 origin/離線/空 diff)→ Task 2 安全預設 + Task 3 橫幅與空清單提示。✓

**Placeholder scan:** 無 TBD;git4idea API 給了主方案 + fallback + 「以編譯為準」的明確處理方式,非空泛佔位。

**Type consistency:** `GitClipboardPayloadBuilder.build(...)`/`Payload`、`BranchDiffProvider.diffChanges/remoteStatus/candidateBaseRefs`/`RemoteStatus`、`GitSelectionCollector.Selection` 欄位、`SelectionSource.GIT_LOG_OR_HISTORY`、`GitContentResolver.resolve` 於各 task 用法一致。
