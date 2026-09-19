# 還原資料遺失與淺層 clone 複驗 — 2026-09-19 (v1.2.11)

## 結論與範圍

本次是「止血」發版：只修四個會造成**靜默資料遺失**的缺陷，以及一個會讓錯誤無從下手的訊息。
依賴升級、建置瘦身與記憶體最佳化**全部延後**，不在本版內。

缺陷來自一次 191 個代理的全流程對抗稽核（7 維度 finder → 每個發現經
correctness / reproducibility / reachability 三視角、2 票才通過 → 完整性批判 → 第二輪）。
共 49 項確認發現（1 P0、8 P1、21 P2、19 P3），本版只處理下列四項；
其餘 45 項未修，其中 18 項牽涉跨工具剪貼簿契約，必須與 VS Code 端同步處理。

## 本版修正

1. **[P0] 還原會把佔位註解寫成檔案內容**（`RestorePlan.kt`）
   複製端對無法內嵌的檔案（超過大小上限、讀取失敗）會用一行註解代替內容，
   而還原端對此毫無認知，於是 600 KB 的原始檔被換成 46 bytes 的
   `// File skipped: size exceeds limit (…)`。現在這類條目列為 skipped 並在對話框說明。
   涵蓋三種佔位符：`// File skipped: …`、`// Unable to read file content`、`// Error reading file content`。
2. **[P1] 二進位 blob 被當文字解碼**（`GitContentResolver.kt`、`GitBatchContentReader.kt`）
   revision 讀取用 charset 解碼且對二進位輸入從不失敗，PNG 會變成 U+FFFD 亂碼進入 payload，
   Paste & Restore 再寫回去就毀掉原始資產。git 路徑現在比照一般檔案複製路徑
   （`CopyFileContentAction.isBinaryFile`）拒絕，行為也因此與 VS Code 端一致。
3. **[P1] 還原內容被專案預設編碼靜默破壞**（`RestoreExecutor.kt`）
   `VfsUtil.saveText` 以檔案 charset 編碼，新建檔案取的是**專案預設**
   （在 zh-TW/zh-CN 機器常是 Big5/GBK），Java 編碼器對無法表示的字元靜默寫入 `?`，
   而 `errors` 回報空陣列當成功。現在僅在「必然損毀」時把該檔改以 UTF-8 寫入。
4. **[P1] 淺層 clone 的邊界 commit 被當成初始 commit**（`GitContentResolver.kt`）
   `git rev-list --parents` 在淺層邊界會隱藏 parent，程式誤判為 root commit 而把
   **整棵 tree** 當成本次淨變更複製。現在以 `git rev-parse --is-shallow-repository` 區分，
   分不出來就拒絕並保留剪貼簿。同時 `onThrowable` 改為帶出 `VcsException` 訊息，
   使用者才知道要 `git fetch --unshallow`（原本只有一句通用錯誤）。

## 自動驗證

- `./gradlew clean build buildPlugin --console=plain`：**299 tests、0 failures/errors/skipped**，BUILD SUCCESSFUL（1m11s）。
  （v1.2.10 為 295，本版 +4 個回歸測試。）
- 四個新回歸測試**先紅後綠**：逐一停用對應修正後重跑，四個測試全部失敗；還原修正後全部通過。
  - `RestorePlanBuilderTest.skips placeholder bodies instead of overwriting the real file`
  - `RestoreExecutorTest.testRestoredContentSurvivesANonUnicodeProjectEncoding`（實際把專案預設設為 Big5）
  - `MergeCommitCopyTest.testShallowBoundaryCommitIsRefusedInsteadOfCopyingTheWholeTree`（真 `--depth 1` clone，需 `file://`）
  - `MergeCommitCopyTest.testBinaryBlobInACommitIsNotDecodedAsText`
- `./gradlew verifyPlugin`：**四個 IDE 全部 Compatible，0 個相容性問題**
  （IC-252.28539.33、IU-253.32098.37、IU-261.22158.277、IU-262.10315.125；
  各 1 個 deprecated API、4 個 experimental API 使用，與既有狀態相同）。
  v1.2.9 與 v1.2.10 都漏跑這一步，本版補上。
- 未改 wire format、統計演算法或 `EXPECTED_FIXTURES_SHA`；`ContractFixturesTest` 仍綠。

## GUI 實機複驗

Linux + Xvfb `:99`（1920x1200）隔離顯示器，全新 sandbox，`Loaded custom plugins: ClipCode (1.2.11)`。
`:0`（使用者桌面）全程無任何 IntelliJ 視窗。

- 目標 merge 右鍵 → `ClipCode: Copy Full Source`：剪貼簿 **561 bytes，與 v1.2.10 那次的結果逐位元組相同**，
  且對 git 推導的 oracle 重跑驗證 ALL PASS（5 個檔案路徑集合、標籤、完整內容、跨平台路徑形狀、污染排除）。
  → 四個修正**沒有改變任何已驗證的行為**。
- IntelliJ 原生變更清單仍只顯示 `src/Account.java` 1 個檔（平台的 combined view 行為，非本外掛）。

## 已知、未修、不可誤稱已處理

- 全流程稽核的其餘 **45 項**發現未修，包含 8 個 P1 中的 4 個
  （postText 被併入最後一個檔案、CRLF 與自訂 headerFormat 的逃逸不對稱、
  RestoreBase 路徑推斷把檔案移錯位置）。其中 18 項牽涉跨工具契約。
- IDE 記錄到 `ThreadingSupport$LockAccessDisallowed`，堆疊全部位於
  `git4idea.ui.branch.dashboard.FilteringBranchesTree`，平台自標
  `Plugin to blame: Git version: 252.28539.33`，ClipCode 框架數 **0**。與本外掛無關。
