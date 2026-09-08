# ClipCode 1.2.7：歷史批次讀取與分段還原驗證

日期：2026-09-08。接續 v1.2.6，這次實際完成先前保留的兩項優化，沒有修改剪貼簿跨工具格式、Git 設定或 release workflow。

## 實作

- 歷史內容：固定 commit 的普通 GitContentRevision，先讀 IntelliJ 原生有界快取；缺少的內容按 repository、每 32 個 request 使用 Git cat-file 批次讀取。單個 blob 超過 128 KiB 交回原生 reader，每批原始內容上限約 4 MiB，不建立持續成長的自訂快取。
- 正確性：依 byte length 分割 batch response，保留空檔、編碼、空白與 Unicode 路徑；換行路徑、特殊 revision、需要 Git filter/textconv 的檔案保留原生讀取。先檢查 config / attributes；不能確認轉換設定或 batch 失敗時回退，不以空字串冒充成功。
- Git 的轉換後 batch 輸出可能仍標示轉換前大小，已用實際 CRLF 情境重現。因此不對轉換後 stream 套用原始大小解析，避免內容位移污染後續檔案。
- 還原：背景工作依最多 32 檔、合作式約 8 ms 預算切分 write commands，批次間釋放 EDT/write lock，更新進度並檢查取消。單次慢速檔案系統操作不能被這個預算強制中斷。
- 保留 IntelliJ Undo：連續批次共享 group ID；文字覆寫走 Document，混合換行與 binary 保留原始 bytes Undo，刪除先載入原內容。不同還原操作不合併。
- 原生 VCS ignore provider 僅在當前命令內忽略已規劃路徑的 add/remove 提示，finally 清除。不自動 staging、不變更使用者的 Git 提示偏好。

## 可重跑測試

```sh
./gradlew clean build koverXmlReport --no-build-cache
./gradlew verifyPlugin
./gradlew test --tests '*BatchOperationsTest' --tests '*GitBatchContentReaderTest'
```

完整乾淨建置 `BUILD SUCCESSFUL in 31s`；279 tests、0 failures、0 errors、0 skipped，比 v1.2.6 新增 11 項。

涵蓋：200 個真實 Git 歷史檔案與不同工作區內容、三種 native content conversion mode、CRLF attributes、UTF-16、Unicode/空格/tab/換行檔名、空檔、損壞與截斷 batch response、取消、300 檔分段寫入、1,000 檔背景 UI 事件、混合新增/覆寫/刪除 Undo/Redo、混合換行與 binary bytes、獨立 Undo group，以及 VCS ignore 範圍與清除。

既有 Git 整合測試的 repository 初始化改在背景執行，避免 EDT 與背景 VFS lock 互等。新增 Git fixture 主動建立目錄，消除 light-project 重用時對測試執行順序的依賴。

Kover 依現有排除規則：1,403/1,536 lines（91.3%）、856/1,193 branches（71.8%）。GitBatchContentReader 91/99 lines；RestoreExecutor 91/94 lines。這不是整個 IDE UI 的覆蓋率：既有 Swing/Action wrapper/@IdeBoundCode 排除仍存在，沒有為提高數字而增加排除。

## 同機量測

Linux、真實暫存 Git/VFS、相同測試情境。時間為單次驗證樣本，包含測試環境成本，並非跨硬體保證或統計 p95。

| 情境 | v1.2.6 baseline | v1.2.7 最終完整測試 |
|---|---:|---:|
| 200 歷史檔案首讀 | 1,793.9 ms | 499.7 ms |
| 同 200 檔再次讀取 | 625.1 ms | 90.4 ms |
| 300 × 4 KiB 還原總耗時 | 977.9 ms | 726.6 ms |
| 上述最長 write command | 947.5 ms，單一命令 | 15.0 ms，73 命令 |
| 1,000 × 4 KiB 背景還原 | 未量測 | 2,447.1 ms |
| 上述最長 write command / 完成前 UI 事件 | 未量測 | 16.2 ms / 197 次 |

baseline 的「300 檔不能占用一個 write command」斷言先失敗，實作後通過。UI 事件測試明確要求還原完成前收到多次事件；沒有以脆弱的固定毫秒門檻取代行為驗證。

## 真實 IDE 操作

IntelliJ IDEA 2026.2.2，Linux 圖形桌面，獨立 sandbox 與 `/tmp/clipcode-batch-gui` 測試 repository，實際載入本次 production classes。

1. 剪貼簿放入 1,000 個檔案，快捷鍵啟動 Paste & Restore，確認對話框後完成，逐檔核對內容完全相同。沒有還原期間逐批 Add Files to Git 對話框；這個問題在第一輪 GUI 測試確實出現，修正後重啟驗證。
2. 真實 Ctrl+Z 需要兩次（先剩 33 檔，再全部移除）。這證明真實 IDE 的 Undo 分組與 headless 連續命令測試不同；可能有 IDE 命令介入，未捕捉命令 trace，不能斷言具體介入者。不能承諾所有環境永遠單次 Undo。
3. 兩次 Redo 後 1,000 檔內容逐一相同。原生 Redo 會出現 Git 加入提示，取消提示後內容仍正確，沒有選擇「Don't ask again」或自動加入 Git。
4. Git Log 選取「200 historical files」提交，變更清單全選，實際點 ClipCode: Copy Full Source。剪貼簿 200 個 header，逐項包含 committed 內容，沒有 working tree 的內容。
5. GUI 測試期間檢查 sandbox idea.log，未見 ERROR。上述測試證明操作正確與批次間可響應，不是任意 repository 的 Git graph benchmark，也不證明 Mac 的原生 Git graph 卡頓已被修復。

## API 與相容性

最終 `./gradlew verifyPlugin`：BUILD SUCCESSFUL in 3m 4s。2025.2.6.1（252.28539.33）、2025.3.4（253.32098.37）、2026.1（261.22158.277）、2026.2.2（262.10315.125）全部 Compatible；每個版本均列出 4 處 Experimental API 使用，未列出 deprecated/internal API 使用或相容性問題。使用的兩個公開 API `CommandProcessor.allowMergeGlobalCommands` 與 `VcsFileListenerIgnoredFilesProvider` 標記為 Experimental；不是 deprecated/internal，但未來 IDE 可能變動，因此不能宣稱零 API 風險。沒有使用曾評估的 Internal `VcsFileListenerContextHelper`。

GitHub Release ZIP 是交付產物；缺 Marketplace token 的 workflow 最後一步失敗屬既有設定，不影響已建立的 GitHub Release。
