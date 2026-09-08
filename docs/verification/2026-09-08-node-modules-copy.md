# node_modules 複製完整性驗證（1.2.8）

## 結論與重現

2026-09-08，針對使用者擔心的「截斷／複製不完全」建立 NodeModulesCopyTest，以原始檔案逐一比對 payload、解析結果及系統剪貼簿讀回值。

v1.2.7 並未完全解決所有情況。實際發現並修正：

1. IntelliJ 將 node_modules 標為 library root 時，ExternalLibraryHandler 的文字白名單不含 `.mjs`、`.cjs`、source map、無副檔名 LICENSE 等。原始 8 檔測試只得到 4 檔。加入 `.mjs/.cjs/.mts/.cts/.map` 與標準無副檔名文字文件；沒有改成任意未知檔案都當文字。
2. 使用者已把單檔限制調高至 20,000 KB，library handler 仍額外靜默略過大於 10 MiB 的檔案。11 MiB bundle 得到 0 檔。移除重複硬上限，由共用複製入口統一依使用者設定限制及提示。
3. 目錄 symlink 指回目前祖先時，遞迴重複讀取，最後報 Too many levels of symbolic links。共用目錄遍歷加入 canonical path 的當前祖先集合，finally 移除；只擋循環，正常 package alias 保留所選路徑，不做全域 canonical 去重。

3 MiB bundle 在修正前已完整，1,500 個巢狀一般檔案也完整；沒有證據顯示這兩個案例存在字串截尾。因此不把漏檔、設定限制與真正截斷混成同一原因。

## 自動化測試

```sh
./gradlew test --tests '*NodeModulesCopyTest'
./gradlew clean build koverXmlReport --no-build-cache
./gradlew verifyPlugin
```

完整乾淨建置 `BUILD SUCCESSFUL in 42s`，286 項測試、0 failures/errors/skipped。新增 7 項實際 VFS 測試：

- 1,500 個巢狀檔案，包含中文、emoji、像 header 的原始內容，逐一完整比對。
- 真正註冊 module library 的 node_modules，11 種套件文字檔名／格式均保留。
- 3 MiB 與 11 MiB bundle 分開核對完整內容與結尾，並透過正式 copyToClipboard 寫入／讀回系統剪貼簿。
- 預設 30 檔會回報 fileLimitReached；501 KiB 檔案在 500 KB 設定下回報 size skipped，payload 有明確略過標記。
- 循環 symlink 不重複內容；正常 `.pnpm/package` symlink 可複製並保留 `node_modules/package/index.js` 路徑。

## 真實 IntelliJ 操作

IntelliJ IDEA 2026.2.2、Linux 圖形桌面、獨立 `/tmp/clipcode-node-gui` 測試專案。

- 專案透過 module-library 設定 node_modules；Project 樹實際顯示 library root。
- 明確關閉檔數上限，設定 maxFileSizeKB=20000。
- 1,000 個巢狀 `.js`、8 個特殊格式／檔名、1 個 11 MiB bundle，共 1,009 檔。
- 在 Project 樹選取 node_modules，Find Action 執行 `ClipCode: Copy to Clipboard`。
- 以 xclip 從作業系統讀回：13,645,628 字元；1,009 個 header。逐檔核對 header + 完整原始內容，包括大檔最後 END，全部通過。idea.log 無 ERROR。
- 本輪 GUI 證明 library 格式與大檔修正；隨後加入的循環連結防護由上述自動化 VFS 測試驗證，未把不同測試範圍混稱為 GUI 全覆蓋。

## 邊界

- 預設仍是最多 30 檔、單檔 500 KB；要複製大型套件需自行調整。此限制會造成有提示的部分複製，不等於截尾。
- 二進位檔、未支援的未知 library 格式與使用者排除規則仍不會當成文字複製；本功能不是 node_modules 的位元組備份工具。
- 尚未量測數 GB payload 或任意外部程式的貼上上限；本次 Linux 剪貼簿成功不保證所有 macOS 接收程式都能貼同樣大小。
- 未修改跨工具 clipboard-contract.json，原有 parser 的空白行／換行處理契約保持不變。

## 發版檢查

1.2.8 ZIP 的 `verifyPlugin`：BUILD SUCCESSFUL in 2m 47s；2025.2.6.1、2025.3.4、2026.1、2026.2.2 全部 Compatible。仍為既有兩個公開 Experimental API 的 4 處提醒，沒有新增 deprecated/internal API 或相容性錯誤。
