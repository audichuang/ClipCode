# Linux 實機與 Git Log 效能複驗 — 2026-09-08

## 結論與範圍

已 pull 遠端 `ef8d590`（1.2.5），保留本機 `390b6cc` 文件提交，以 merge 整合；沒有推送或發布。
本次是在 Linux 桌面上重新建置、啟動真實 IDE、操作 UI、讀回剪貼簿及檔案，並錄製 JFR，並非沿用前次 macOS 報告的結論。

**尚未重現使用者原專案的 Git 線圖卡頓，不能宣稱已修好那個問題。**
在含 5,499 個 commit、500 個本機分支（499 個側分支及 main）、499 次合併的隔離 repo 上，
啟用及停用 ClipCode 的線圖操作沒有顯示外掛是持續負載來源。修正了另行實測確認的複製問題。

## 自動驗證

- Pull 後原版本：`./gradlew clean build koverXmlReport --warning-mode all`，263 tests、0 failures/errors/skipped，BUILD SUCCESSFUL，57 秒。
- 本次修正後：`./gradlew build koverXmlReport`，266 tests、0 failures/errors/skipped，BUILD SUCCESSFUL，22 秒；測試案例時間合計約 15.33 秒。
- 兩個新回歸測試先紅後綠：空檔與非空檔共同往返、第一個檔案取消後不繼續走訪資料夾。
- 新增 Git action update 成本檢查：10,000 次約 1.72 ms，只要求 `project` data key，不讀 changes、檔案或 commit 內容。時間是觀察值，測試以存取範圍作穩定斷言。
- 兩個 repo 的 `clipboard-contract.json` 仍 byte-identical；本次沒有改 wire format、統計算法或 SHA pin。
- Kover 有既有 UI／IDE-bound 排除；不可把其 coverage 當成全產品 GUI 覆蓋率。

原版本及本次修正版均以 Plugin Verifier 1.410 對四版全部回報 Compatible，無 deprecated/internal/experimental API 使用報告；修正版驗證 BUILD SUCCESSFUL，2 分 57 秒：

| IDE | Build |
| --- | --- |
| Community 2025.2.6.1 | IC-252.28539.33 |
| IDEA 2025.3.4 | IU-253.32098.37 |
| IDEA 2026.1 | IU-261.22158.277 |
| IDEA 2026.2.2 | IU-262.10315.125 |

`build.gradle.kts` 已將 2026.2.2 加入常規矩陣，最低編譯平台仍為 2025.2.6.1，以保留 2025.2+ 支援。
測試 fixture 仍有既有 deprecated API 編譯警告，這些類別不在交付 ZIP 中。

## 真實 GUI 操作

### 2025.2.6.1，pull 後的 1.2.5

- 啟動 sandbox、開啟隔離 Git repo，確認外掛載入。
- PR 面板選 origin/main，比較 feature，顯示 `[MODIFIED] src/Main.kt`。
- PR Copy 讀回的是 `committed`，沒有把不同的 `working-tree` 內容混進來。
- Project tree copy 讀回 `working-tree`；一次從送出 action 到剪貼簿可讀約 52 ms（含桌面自動化與輪詢成本，非保證值）。
- Paste & Restore 能建立缺少的檔案。
- 從外部 shell 刪檔而 IDE VFS 尚未刷新時，曾顯示 skip；Reload All from Disk 後可還原。後續改用 IDE 自身 Delete 操作測往返，避免將外部檔案同步時間當成 UI 邏輯錯誤。

### 2026.2.2，套用本次修正的本機 build

- 真實啟動 JBR 25.0.4 的 IDEA 2026.2.2，與建置產物的 plugin JAR SHA-256 比對一致。
- Project tree 選 `src`，執行 Copy to Clipboard，讀回 `Main.kt` 及零位元組的 `Empty.txt`。
- 通知實際顯示 2 files，以及 114 characters / 9 lines / 13 words / 13 estimated tokens。
- IDE Delete 整個 `src`，再按 Paste & Restore，對話框列出正確的兩個絕對目標。
- 按 Proceed 後，磁碟內容確認 `Main.kt` 為 `val message = "working-tree"`、`Empty.txt` 是零位元組。
- PR 面板 Copy 與 Git Log changed-files 右鍵 Copy Full Source 均讀回 `[MODIFIED] src/Main.kt` 與 `committed`。Git Log 操作前先將剪貼簿改成 sentinel，確認此次 action 確實更新了內容。
- 本輪複製／還原沒有 ClipCode runtime ERROR。

這不是每一種 OS、遠端倉庫、Git LFS、所有設定組合的 GUI 驗收。

## Git 線圖啟用／停用對照

在同一 IDEA 2026.2.2、相同 sandbox 配置與同一合成 repo，分別啟用 ClipCode、及用空的外掛目錄並移除強制載入參數重啟。
停用狀態已查 startup log，確認沒有載入 ClipCode（Gradle runIde 會強制載入開發外掛，單改 disabled_plugins.txt 不足以停用）。

每輪 JFR profile 35 秒，實際用鍵鼠連續切換 100 個 commit、向下／上捲動各 80 次，再向上切換 100 個 commit。
效能錄製時沒有同時跑 Gradle 測試或 verifier。兩輪測試從同一線圖位置開始；重啟與快取差異仍使其不能當作嚴格效能 benchmark。

| JFR ExecutionSample | 啟用 | 停用 |
| --- | ---: | ---: |
| 全部 CPU 樣本 | 667 | 640 |
| EDT 樣本 | 485 | 452 |
| 包含 ClipCode frame | 0 | 0 |

兩輪 UI 都能完成操作，錄製期間沒有新增 UI freeze 記錄。啟用輪可見 Swing HTML、Git Log cell renderer、ContainingBranchesPanel 等 IDE 路徑。
樣本數不等同耗時或 FPS；0 個外掛樣本也不是「所有環境絕無問題」的證明。現在的證據只是不支持外掛在這個捲動情境造成重複 Git 工作。

啟動紀錄中有一次約 5 秒 freeze 報告；thread dump 是等待使用者回覆 Trust Project 對話框，未見 ClipCode frame，不能當成線圖重現。

## 實測問題及修正

1. **資料夾取消不及時**：原程式只在最外層選取迴圈檢查 indicator。單選一個資料夾時，第一檔取消後仍走完 30 檔。把同一 indicator 帶入 CopySession，在檔案／子目錄入口與組裝完成前檢查，取消會傳遞出去，不把半成品寫入剪貼簿。
2. **空檔被漏掉**：讀到空字串就 skip。現在成功讀取的零位元組文字檔也會輸出 header；一般檔案讀取失敗回傳 null，不冒充成功的空檔。
3. **大 payload 統計占用 EDT**：單線性掃描仍有可量測成本；通知共用入口改在背景 pool 計算，普通／Git／PR 複製一起受益。保留唯一共用入口與原本四項統計。

無 coverage agent、預熱 10 次後取 15 次中位數，直接量測原有 TokenEstimator.stats：

| UTF-16 code units 規模 | 中位數 | 最大值 |
| --- | ---: | ---: |
| 約 1 Mi | 1.63 ms | 1.77 ms |
| 約 15 Mi | 25.21 ms | 26.21 ms |
| 約 100 Mi | 166.69 ms | 185.08 ms |

這是字元單位而非檔案 bytes；只量 stats，不含內容讀取或 OS clipboard 成本。優化移走 UI 工作，沒有宣稱掃描算法本身變快。

## 仍可優化、但不混為線圖根因

- RestoreExecutor 在同一 write command 內執行整批操作。含 coverage 的平台測試中，30／300／1,000 個 4 KiB 小檔約 97／773／2,677 ms；不是純磁碟 benchmark。大量還原可能占住 EDT，需要另做分批寫入、單一 Undo 群組及取消後部分完成的語意設計。本輪未改這段寫入語意。
- 大量歷史檔案仍有逐檔 revision I/O；前次 200 檔量測不等於線圖捲動耗時。本輪未加入快取或批次 Git 協定。
- 單純把 Task.Backgroundable 改寫成 coroutine，不能證明線圖更快。既有 API 仍通過 verifier；對新的耗時工作應遵循官方背景執行建議。

要確認使用者真正的卡頓，下一份證據應是原專案在卡頓時的 CPU snapshot／thread dump，並記錄是捲動、切換 commit、右鍵、複製或開 PR 面板觸發。

## 證據與重跑

- 常規測試：`./gradlew build koverXmlReport`
- 相容性矩陣：`./gradlew verifyPlugin`
- 新回歸：`./gradlew test --tests '*CopyResponsivenessTest'`
- 本機完整測試結果：`build/reports/tests/test/index.html`
- 本機相容性結果：`build/reports/pluginVerifier/`
- 本機 CPU 錄製／audit 輔助程式：`build/reports/runtime-audit/`（未版本控制）。
- ZIP：`build/distributions/ClipCode-1.2.5.zip`，是未發布的本機修正版，不是遠端 release 的原始 ZIP。
- ZIP SHA-256：`d8489f9e4da1e00e13617c4261812c920bf1d13f8325306d2e84f98600130aa2`。

官方依據：[Action update](https://plugins.jetbrains.com/docs/intellij/action-system.html)、[Threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)、[收集實際卡頓證據](https://intellij-support.jetbrains.com/hc/en-us/articles/207241235-Reporting-performance-problems)、[2026.2.2 發布](https://blog.jetbrains.com/idea/2026/09/intellij-idea-2026-2-2/)。


## 1.2.6 發布前補驗

在上述實測之後，使用者授權驗證通過即發布。版本升為 1.2.6，補上兩項平台回歸：最後一檔取消不得回傳半成品，以及資料夾／空檔重疊選取（兩種順序）均只輸出每檔一次。

`./gradlew build koverXmlReport`：BUILD SUCCESSFUL in 28s；268 tests，0 failures、0 errors、0 skipped。五項新增測試是同一 CopyResponsivenessTest 的平台測試，並非僅對 mock 複製實作邏輯。

測試缺口仍包括真實遠端網路失敗、各作業系統剪貼簿、所有設定組合與 macOS 渲染問題。現有 Kover 排除 IDE-bound 程式，故不能以其百分比保證完整產品覆蓋。取消、空檔和資料來源有直接斷言；GUI 路徑另以實際操作及剪貼簿／磁碟讀回驗證。

1.2.6 ZIP 內 plugin.xml 版本及 change notes 已驗證；96 個 production class 的 bytes 與前述 GUI 實測的本機修正版 1.2.5 完全相同。新增變更僅為版本、發布說明及測試，沒有再改 production class。跨工具 fixture 仍完全一致。

1.2.6 本機 ZIP SHA-256：`d2cba1f18a10125b47064faffb891dc47a7bafdbf3df86dedd33d5d1baadf540`。

1.2.6 最終 ZIP 的四版 Plugin Verifier 全數 Compatible，`BUILD SUCCESSFUL in 2m 49s`（2026-09-08）。
