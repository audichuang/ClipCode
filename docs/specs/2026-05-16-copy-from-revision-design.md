# Copy from Revision — 設計規格

- **日期**: 2026-05-16
- **狀態**: 設計階段，等待使用者確認（v2，採納 Codex review）
- **作者**: audichuang
- **影響範圍**: `CopyGitFilesContentAction`、`GitContentResolver`、`GitSelectionCollector`

---

## 1. 背景與問題

ClipCode 的「Copy Full Source」action 在 Git Log 視窗多選 commit 範圍時，輸出的檔案內容是 **working tree 當前的內容**，而不是「選中範圍最新 commit 時的檔案狀態」。

### 使用者實際遭遇的情境

- 公司端在 Git Log 視窗多選一段 commit 範圍（例如 `release/20260526` 上連續 N 個 commit）
- IDE 右側顯示這個範圍變更過的檔案清單
- 使用 Copy Full Source 複製
- **期望**：拿到「該範圍最新 commit 時的檔案內容」
- **實際**：拿到 working tree 當前的內容（可能後續又被改過，與該 commit 時的狀態對不上）

### 根因（程式碼層級）

`GitContentResolver.resolveChange`（L100–118）：

```kotlin
val virtualFile = if (changeType == DELETED) null
                  else change.afterRevision?.file?.virtualFile
                       ?: change.beforeRevision?.file?.virtualFile
                       ?: findFile(filePath)

val contentFromRevision = if (virtualFile == null || changeType == DELETED) {
    readRevisionContent(change) ?: ...
} else {
    null  // ← 一旦 working tree 找得到檔，就不讀 revision content
}
```

下游 `CopyGitFilesContentAction.appendContent` 也是「virtualFile 優先，contentFromRevision 後備」。即便 IntelliJ 已經把 Change 標成「來自 commit X 的 revision」，ClipCode 還是抓 working tree。

DELETED 路徑還有額外問題：`resolveDeletedContent` 會 fallback 到 `ChangeListManager.allChanges` 或 `git show HEAD:path`，對 Git Log 歷史的 DELETED 而言，會抓到「目前 HEAD 或 local change」，**而不是選中 revision 當下的內容**。

---

## 2. 目標

修正 `GitContentResolver` 與 `CopyGitFilesContentAction` 在 **Git Log 來源** 時的內容讀取行為：

1. MODIFIED / NEW / RENAMED：強制讀 `afterRevision.content`，不 fallback working tree
2. DELETED：強制讀 `beforeRevision.content`，**不**走 `resolveDeletedContent` 的 HEAD fallback
3. Local Changes / Staging Area / Commit UI 行為一律不變

## 3. 非目標（明確不做）

- ❌ 不新增 action
- ❌ 不改 UI（右鍵選單、設定面板）
- ❌ 不改剪貼簿輸出格式
- ❌ 不改 `plugin.xml` 註冊
- ❌ 不改 `PasteAndRestoreFilesAction`、`RestorePlan`、`RestoreExecutor`
- ❌ 不影響 Local Changes / Staging Area / Commit UI 的現有行為
- ❌ 不改 `copySelectedFilesWithoutGitMetadata` 路徑（無 Change metadata 時仍走 working tree）

---

## 4. 設計（方案 A'：source 判斷 + revision 類型輔助）

### 4.1 為什麼不能只看 revision 類型

Codex review 指出：Local DELETED 的 `beforeRevision` 是指向 HEAD 的 `GitContentRevision`，Git Log DELETED 的 `beforeRevision` **也是** `GitContentRevision`。靠 `isLocalRevision(revision)` 兩者都會被誤判為「歷史 commit」，導致 Local DELETED 失去 fallback 能力、Git Log DELETED 走錯 HEAD。

**結論**：主判斷必須用 **selection source**（從 collector 階段就知道），revision 類型只當輔助。

### 4.2 SelectionSource enum（加在 `GitSelectionCollector`）

```kotlin
enum class SelectionSource {
    LOCAL_CHANGES_OR_COMMIT_UI,  // ChangesTree、Commit dialog、staging area
    GIT_LOG_OR_HISTORY,          // Git Log、Compare、Annotate 等歷史檢視
    UNKNOWN                      // 保守 fallback，當作 GIT_LOG 處理
}
```

`Selection` data class 新增 `source: SelectionSource` 欄位。

#### 偵測規則（在 `GitSelectionCollector.collect()` 設定）

| Data context 線索 | source |
|---|---|
| `VcsDataKeys.COMMIT_WORKFLOW_UI` 或 `COMMIT_WORKFLOW_HANDLER` 非 null | `LOCAL_CHANGES_OR_COMMIT_UI` |
| `component is ChangesTree` 且至少一個選中 Change 命中 `ChangeListManager.allChanges`（用 `contains` 或 path 比對） | `LOCAL_CHANGES_OR_COMMIT_UI` |
| 其他（Git Log、Compare、Annotate 等） | `GIT_LOG_OR_HISTORY` |
| 沒有 Change 也沒有 commit UI 線索 | `UNKNOWN` |

### 4.3 Git Log 多選 commit 的範圍語意

仰賴 IntelliJ Git Log 視窗的內建行為：

- 多選 commit 時，IntelliJ 自動將範圍 squash 成 Changes 列表
- 每個 Change 的 `afterRevision.revisionNumber` = 「範圍最新 commit」
- 每個 Change 的 `beforeRevision.revisionNumber` = 「範圍最舊 commit 的 parent」

**ClipCode 不需自行計算範圍**，只要尊重 IntelliJ 提供的 `afterRevision.content`，就是「範圍最新 commit 的檔案狀態」。

### 4.4 Resolver 行為（依 source 分流）

| source | MODIFIED / NEW | RENAMED | DELETED |
|---|---|---|---|
| `LOCAL_CHANGES_OR_COMMIT_UI` | working tree（現行為） | working tree | `resolveDeletedContent` 完整鏈：ChangeListManager → git show HEAD（現行為） |
| `GIT_LOG_OR_HISTORY` / `UNKNOWN` | **`afterRevision.content`**，`virtualFile = null` | `afterRevision.file.path` + `afterRevision.content` | **`beforeRevision.content`**；不走 `resolveDeletedContent` |

#### 「不 fallback working tree / HEAD」是刻意設計
避免使用者拿到 working tree 或 HEAD 內容卻誤以為是 commit 版本，發生隱性錯誤。失敗時列入 `unresolvedEntries`，notification 提示具體哪些檔抓不到。

#### 輔助 helper（保留作為第二道防線）

```kotlin
private fun isLocalRevision(revision: ContentRevision?): Boolean {
    if (revision == null) return false
    if (revision is CurrentContentRevision) return true
    val rev = revision.revisionNumber
    return rev == VcsRevisionNumber.NULL
        || rev.asString().equals("LOCAL", ignoreCase = true)
}
```
若 source 是 `LOCAL_CHANGES_OR_COMMIT_UI` 但 revision 顯示為非 LOCAL（罕見），仍以 source 為準。

### 4.5 RENAMED 既有限制

只輸出 `afterRevision.file.path`，不附 `beforeRevision.file.path`。本次 spec 不改剪貼簿格式，若未來要顯示「from → to」改名軌跡，另開 spec。

### 4.6 修改點清單

1. **`GitSelectionCollector.kt`**
   - 新增 `SelectionSource` enum
   - `Selection` data class 加 `source` 欄位
   - `collect()` 在收集 changes 時，依 §4.2 規則設定 `source`

2. **`GitContentResolver.kt`**
   - `resolve()` 多接一個參數 `source: SelectionSource`（或從 `selection.source` 讀）
   - `resolveChange` 改判斷邏輯：
     - source = LOCAL → 走原有 virtualFile 優先邏輯（不變）
     - source = GIT_LOG / UNKNOWN → 強制 `contentFromRevision = readRevisionContent(change)`、`virtualFile = null`
   - DELETED 分流：source = LOCAL 才呼叫 `resolveDeletedContent`；其他來源只讀 `beforeRevision.content`
   - 新增 helper `isLocalRevision`（如 §4.4）

3. **`CopyGitFilesContentAction.appendContent`**
   - 對應修正優先順序：當 `entry.contentFromRevision` 不為 null，**優先使用** `contentFromRevision`，不去讀 `entry.virtualFile.contentsToByteArray()`

4. **fast path 推論（不需改但要驗證）**
   - `handleResolvedEntries` 的 fast path 條件：`contentEntries.all { it.hasVirtualFileContent } && deletedMarkerEntries.isEmpty()`
   - Git revision 的 Change 在新邏輯下 `virtualFile = null`、`hasVirtualFileContent = false`，fast path **不會被觸發**
   - 結果：Git Log 來源一律走 `copyResolvedEntries` 路徑（含 revision content）
   - **`copySelectedFilesWithoutGitMetadata` 路徑不改**：當 selection 無 Change metadata 時仍走 working tree

5. **不修改**：`ChangeTypeLabel`、`GitClipboardFormatter`、`ClipboardPathResolver`、所有 Paste / Restore 路徑、`plugin.xml`

---

## 5. 回歸保證

| 情境 | source 判定 | 修正前 | 修正後 |
|---|---|---|---|
| Local Changes 視窗（未提交變更）| LOCAL | 抓 working tree | 抓 working tree（不變） |
| Staging Area（已 staged）| LOCAL | 抓 working tree | 抓 working tree（不變） |
| Commit UI（commit dialog）| LOCAL | 抓 working tree | 抓 working tree（不變） |
| Local DELETED 檔 | LOCAL | `resolveDeletedContent` 鏈 | 同上（不變） |
| Project View 右鍵檔案 | n/a（不進 resolver）| 走 `CopyFileContentAction` | 不變 |
| Editor Tab 右鍵 | n/a | 走 `CopyAllOpenTabsAction` | 不變 |
| Paste and Restore 解析、覆蓋、刪除 | n/a | 既有行為 | 不變（完全不動）|
| **Git Log 單選 / 多選 commit MODIFIED/NEW** | GIT_LOG | **抓 working tree（bug）** | **抓 `afterRevision.content`** |
| **Git Log 單選 / 多選 commit DELETED** | GIT_LOG | **抓 HEAD 或 ChangeListManager（bug）** | **抓 `beforeRevision.content`** |
| 無 Change metadata 的純檔案選取 | UNKNOWN（走 `copySelectedFilesWithoutGitMetadata`） | working tree | 不變 |

---

## 6. 測試策略

採納 Codex 建議：**resolver / action level 必須自動化**，UI level 才用手動。

### 測試檔配置
- **新增** `src/test/kotlin/com/github/audichuang/clipcode/GitContentResolverTest.kt`
- **新增** `src/test/kotlin/com/github/audichuang/clipcode/CopyGitFilesContentActionTest.kt`
- 既有 `CopyFileContentActionTest.kt` 不動

### 自動化測試（用 `BasePlatformTestCase` + 自訂 `ContentRevision`）

#### 6.1 LOCAL source MODIFIED 路徑不變
- **Given**：`Selection.source = LOCAL_CHANGES_OR_COMMIT_UI`、`Change` 的 `afterRevision = CurrentContentRevision(...)`
- **Expected**：`ResolvedGitEntry.virtualFile != null`、`contentFromRevision == null`

#### 6.2 GIT_LOG source MODIFIED 走 revision
- **Given**：`Selection.source = GIT_LOG_OR_HISTORY`、`Change.afterRevision = GitContentRevision(sha)`、working tree 同檔但內容不同
- **Expected**：`contentFromRevision == <commit 版本>`、`virtualFile == null`、`appendContent` 用 contentFromRevision 不用 virtualFile

#### 6.3 GIT_LOG source DELETED 不走 HEAD fallback（最關鍵的 BLOCKER 測試）
- **Given**：`Selection.source = GIT_LOG`、`Change.type = DELETED`、`beforeRevision.content` 回某 commit 內容，HEAD 上同 path 存在但內容不同
- **Expected**：輸出內容 = `beforeRevision.content`，**不**等於 HEAD 內容；不呼叫 `resolveDeletedContent`

#### 6.4 GIT_LOG source revision content 抓取失敗
- **Given**：`afterRevision.content` 拋例外或回 null
- **Expected**：列入 `unresolvedEntries`，**不** fallback working tree

#### 6.5 LOCAL source DELETED fallback 鏈仍可用
- **Given**：`Selection.source = LOCAL`、`Change.type = DELETED`、`beforeRevision.content` 回 null
- **Expected**：仍會呼叫 `resolveDeletedContent`，從 ChangeListManager / git show HEAD 取得內容

#### 6.6 SelectionSource 偵測
- **Given**：mock 各種 data context（COMMIT_WORKFLOW_UI 有 / 無、ChangesTree + ChangeListManager 命中 / 不命中）
- **Expected**：source 設定正確

### 手動整合測試（UI smoke，記錄到 TESTING_GUIDE.md）

#### 6.7 Git Log 多選 commit 端到端
- 在 `runIde` 開 git repo，Git Log 多選一段 commit
- Copy Full Source、貼到外部文字編輯器
- 驗證：複製出的內容 = 範圍最新 commit 時的內容（與 working tree 不一致時尤其要驗證）
- 驗證：DELETED 檔案的內容 = 該 commit 當下的內容（非 HEAD）

---

## 7. 風險

| 風險 | 緩解 |
|---|---|
| Source 偵測誤判（例如某種 ChangesTree 入口應該歸 LOCAL 但被歸 GIT_LOG） | 測試 6.6 守門；保守起見 UNKNOWN 也走 GIT_LOG 行為（不會誤抓 HEAD） |
| Codex 提到的次要入口（Shelved Changes、Compare、Local History、Annotate）未驗證 | 視作 out of scope；若實際遇到問題再加 source case |
| 抓不到 revision content 時不 fallback，使用者可能誤以為「沒複製到」 | notification 明確提示「unresolved」並列出檔名（沿用現有 `unresolvedEntries` 提示機制）|

---

## 8. YAGNI（明確不做）

- 不加 UI toggle
- 不加新 action
- 不改剪貼簿輸出格式（RENAMED 不顯示 before path）
- 不改 `plugin.xml`
- 不另建「Copy at Revision」獨立 action（方案 C 被否決）
- 不做 ContentRevision 子類別白名單矩陣（使用者自用，非廣泛發佈，YAGNI）
- 不為 Shelved / Compare / Local History / Annotate 等次要入口寫專門 source case（遇到再說）
