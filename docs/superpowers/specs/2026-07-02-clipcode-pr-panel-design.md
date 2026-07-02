# ClipCode PR 面板 — 設計

日期:2026-07-02
狀態:待 review

## 目標

在 ClipCode 加一個可視化的「PR 面板」(IntelliJ Tool Window),讓使用者:

1. 選一個 base ref,看目前分支相對 base 的**全部變更檔案**(等同一個 PR 的內容)。
2. 一鍵用 ClipCode 現有格式把這些變更**複製到剪貼簿**,貼給 AI。
3. 面板會**檢查 remote**,提醒使用者目前分支相對 `origin` 是不是最新的(落後幾個 commit)。

## 範圍決定(已與使用者確認)

- **不接 GitHub API。** 純本地 git,用 git4idea。「PR」= `base..HEAD` 的變更。不需 token、不需認證流程。
- base ref 預設候選:當前分支的 upstream(如 `origin/main`)→ fallback `origin/main` → `origin/master`;使用者可從下拉改選任意本地/遠端 ref。
- **複製格式沿用現有格式**(與從 commit history 複製一模一樣),維持跨工具(ClipCodeVSCode)相容契約。不另做 PR 專屬格式;PR 標題/summary 之類日後有需要再加。
- 面板**只列變更檔清單**(帶 `[NEW]/[MODIFIED]/[DELETED]/[MOVED]` 標籤),不內嵌 diff 檢視;點檔案交給 IDE 內建 diff。這是最省的做法。

## UI(單一 Tool Window「ClipCode PR」)

三塊,由上到下:

1. **頂部列** — base ref 下拉 + `Refresh` 按鈕。
2. **remote 新鮮度橫幅** — 顯示目前分支 vs `origin/<同名>` 的 `ahead N / behind M`。
   - behind > 0:顯示「origin 有 M 個新 commit,建議 pull」,附 `Fetch` 按鈕。
   - fetch 失敗(離線/需認證):退回本地快取的 remote ref,標示「未能連線 remote,以下為本地快取狀態」。
3. **變更檔案清單** — base..HEAD 的變更檔(可勾選,預設全選)+ 底部 `Copy to Clipboard` 按鈕。

## 資料流(以複用既有程式碼為主)

```
選 base ref / Refresh
  └─ 背景 git fetch(失敗則略過,用本地快取)
  └─ BranchDiffProvider(新)
       ├─ 算 base..HEAD 的 List<Change>            (git4idea diff)
       └─ 算目前分支 vs origin/<branch> 的 ahead/behind
  └─ 構造 GitSelectionCollector.Selection(source = GIT_LOG_OR_HISTORY)   ← 複用既有型別
       └─ GitContentResolver.resolve()      ← 完全複用,已能讀 revision 內容
            └─ 顯示清單 + Copy 時交給 GitClipboardFormatter   ← 完全複用,格式不變
```

關鍵複用點:`GitContentResolver` 已能處理非本地 revision 的內容讀取(見 `readSelectedRevisionContent` / `SelectionSource.GIT_LOG_OR_HISTORY` 路徑),所以 PR 面板不需重寫內容解析與格式化。

## 要新增/改的檔案(最小)

- `ClipCodePrToolWindowFactory.kt`(新)— ToolWindowFactory。
- `ClipCodePrPanel.kt`(新)— UI:base 下拉、remote 橫幅、檔案清單、Copy 按鈕。
- `BranchDiffProvider.kt`(新)— git4idea:算 `base..HEAD` 的 Change 清單、ahead/behind、觸發 fetch。
- `plugin.xml`(改)— 加一個 `<toolWindow>` extension。

複製核心與格式邏輯**一律複用**,不新寫,跨工具相容契約不受影響。

## 錯誤處理

- 專案無 git repo / 無 origin:面板顯示明確提示,不丟例外。
- 選的 base ref 不存在:提示重選,不 crash。
- fetch 需認證或離線:如上,退回本地快取並標示。
- base..HEAD 無變更:清單空,顯示「無變更」,Copy 停用。

## 測試

ClipCode 無單元測試框架(見 AGENTS.md),手動測:

1. 有落後 origin 的分支 → 橫幅顯示 behind M + 建議 pull。
2. 與 origin 同步的分支 → 橫幅顯示最新。
3. 離線 → fetch 失敗提示 + 本地快取狀態。
4. base..HEAD 有 NEW/MODIFIED/DELETED/MOVED 各類變更 → 清單標籤正確,Copy 出的文字與從 commit history 複製同格式(用 ClipCodeVSCode 的 round-trip 對照)。
