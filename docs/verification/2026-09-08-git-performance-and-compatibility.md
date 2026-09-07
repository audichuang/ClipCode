# ClipCode Git 與相容性驗證（2026-09-08）

基準：已將 main 快轉至遠端 `e074147`（1.2.4），再套用本次修正。本報告記錄 1.2.5 發版前的程式邏輯驗證；原驗證 ZIP 的版本為 1.2.4，發版另更新版本號及 release notes。

## 驗證結果

- 完整 `build` 成功，包含測試、打包、外掛結構與 Kover 檢查。
- 263 項測試通過，0 failures、0 errors、0 skipped；本次新增 18 項回歸／整合測試。
- Plugin Verifier 1.410：IDEA Community 2025.2.6.1（IC-252.28539.33）與本機 IDEA 2026.2.2（IU-262.10315.125）皆為 `Compatible`，沒有棄用、internal 或 experimental API 使用報告。
- 測試執行環境為 IDEA 2025.2.6.1 的平台測試框架與 JBR 21；2026.2.2 本輪執行二進位相容性檢查，未宣稱已做完整 GUI 操作驗收。
- 測試 fixture 本身仍有既有的 deprecated API 編譯警告；這些測試類別不包含在外掛 ZIP。

## 已重現並修正

| 區域 | 問題與修正 |
| --- | --- |
| API 相容性 | 以兩個 SDK 都提供的 `Application.runReadAction` 取代 `ReadAction.compute`，保留既有逐檔讀取鎖；專案根目錄改用 `Project.basePath`。 |
| Kotlin 預設方法 | 設定 `JvmDefaultMode.NO_COMPATIBILITY`，直接繼承 IDE 的 JVM default methods。編譯後 PR factory 不再生成呼叫棄用／internal 方法的橋接；以 javap 與兩版 verifier 確認。 |
| Staged／Unstaged | Staged 讀 index；staged deletion 讀 HEAD 的刪除前版本；unstaged deletion 讀 index 的刪除前版本。明確的 staging 節點優先於泛用 Change／VirtualFile 資料。 |
| 歷史與本機判斷 | `Change.equals` 只比較路徑，不能用它判斷是否為本機 Change。改為一次本機快照及物件身分比對，並識別 `CurrentContentRevision`；不會因同一路徑把歷史檔案當成本機版本。 |
| 選取內容 | 已有明確 Git 選取時，不再混入 DataContext 的焦點編輯器檔案。 |
| 重複讀取 | 在讀 revision 前去重；100 個重複路徑由讀取 100 次降為 1 次。 |
| 取消 | resolver、Git payload builder、一般複製與外部檔案讀取會傳遞 `ProcessCanceledException`，不再轉成缺少內容或錯誤文字。PR 複製取消／失敗後可以重試。 |
| PR 比較失敗 | 無效 base／無共同祖先不再降級成空結果或兩點比較；PR 顯示失敗，重新載入時清除舊選取，避免複製舊 base 的內容。 |
| 遠端狀態 | rev-list 失敗或無法解析不再回傳 0/0 假裝已同步；沒有 upstream 的分支仍會執行要求的 fetch。 |

編碼與內容邊界另驗證：UTF-16 BOM、中文與空白路徑、index 再次更新、搬移、刪除整個父目錄、二進位檔案。解碼使用 IDE 既有 Git 內容解碼；沒有實體檔案時的 UnknownFileType 不會單獨造成文字刪除內容被跳過。

PR 的 merge-base 三點比較、base 分支另有新提交時排除 base-only 檔案、選取後 HEAD 改變仍複製原 commit，均經隔離 Git repo 整合測試確認。

## 效能判斷

這些是本機平台測試中的觀察值，包含測試框架與 coverage agent；不是跨機器保證。以操作次數作穩定回歸條件，時間只記錄，不設容易波動的毫秒門檻。

| 情境 | 實測 |
| --- | --- |
| 選取 500 個歷史變更，另有 2,000 個本機變更 | 修改前約 209 ms、讀本機快照 500 次；修改後約 0.8–1.5 ms、快照 1 次。消除每個選取項目重新掃描本機變更的成本。 |
| 50 個歷史檔案，每個約 4 KB | 首次約 0.67–0.99 秒；重讀約 0.7–2.4 ms。 |
| 200 個歷史檔案，每個約 4 KB | 首次約 2.5–3.9 秒；重讀約 1.2–3.2 秒，受快取容量與當下系統負載影響。 |
| 單一本機 bare remote 的 fetch + ahead/behind | 約 115–158 ms；網路服務延遲不包含在這個量測內。 |

一般少量檔案已能受益於 IDE 原生 revision cache，不需要額外建立跨操作快取。測試 SDK 的原生 cache 上限為 100 筆，因此不能把 200 檔的重讀稱為全快取命中。大量歷史內容讀取仍有逐檔載入成本；本次改善的是選取與重複讀取，沒有宣稱加速所有歷史 I/O，也沒有新增自訂批次 Git 協定或長期快取。如果主要使用情境是數百／數千檔，批次 blob 讀取值得另外實作並驗證編碼、取消與錯誤處理。

遠端測試使用隔離本機 bare remote／peer clone，確認 fetch 前 ahead=1/behind=0，fetch 後 ahead=1/behind=1；遠端 ref 失效會回報失敗。未模擬大型遠端、慢速網路或 Git LFS。

## 重跑與產物

```sh
JAVA_HOME=/Users/audi/Library/Java/JavaVirtualMachines/azul-21.0.12.1/Contents/Home ./gradlew build --offline
```

本機兩版 verifier 以暫存 init script `/tmp/clipcode-verify-supported.gradle` 指定已安裝／已快取的 IDE，沒有改動專案既有的驗證矩陣：

```sh
JAVA_HOME=/Users/audi/Library/Java/JavaVirtualMachines/azul-21.0.12.1/Contents/Home ./gradlew build verifyPlugin --offline --no-configuration-cache --console=plain -I /tmp/clipcode-verify-supported.gradle
```

- 測試：`build/reports/tests/test/index.html`
- 相容性：`build/reports/pluginVerifier/IC-252.28539.33/report.html`、`build/reports/pluginVerifier/IU-262.10315.125/report.html`
- 可安裝 ZIP：`build/distributions/ClipCode-1.2.4.zip`
- 最終完整記錄：`/tmp/clipcode-124-final-verification.log`

Pull 前的本機舊版號（1.1.9）與舊 AGENTS 記憶時間戳，保存在名為 `codex: preserve local instructions and version before requested pull 2026-09-08` 的 stash，另有 `/tmp/clipcode-before-pull-20260908.patch` 備份。現行版本採遠端 1.2.4；原本的 `test.txt` 未變更。
