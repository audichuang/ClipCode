# 1.2.9 右鍵忽略驗證

新增 Project view 與 editor tab 選單 `ClipCode: Ignore When Copying`。支援單檔、目錄、多選，以既有專案 PATH EXCLUDE 規則儲存並啟用排除。既有相同規則重新啟用而非重複新增；已啟用的 include 設定保留；原本整體篩選關閉時不意外啟用舊 include 限制。更新時替換規則清單，避免修改正在複製工作讀取的清單。

自動化：IgnoreCopyPathsActionTest 3 項測試涵蓋選單狀態、多選去重、停用規則重新啟用、include 狀態，以及實際複製的目錄/單檔排除與同名前綴 sibling 保留。

GUI：IntelliJ IDEA 2026.2.2，Linux 隔離 `/tmp/clipcode-ignore-gui`。Project 樹多選 node_modules 與 local.txt，右鍵點新增項目；未開 Settings。再選專案根執行 ClipCode Copy，作業系統剪貼簿不含兩者的唯一內容標記，仍含 src/main.txt 與 README.md 標記。保存後檢查 `.idea/CopyFileContentSettings.xml`，含兩條 EXCLUDE 規則且 useFilters=true。idea.log 無 ERROR。

本功能直接管理現有 ClipCode 複製排除規則，匹配語意與 Settings 一致。GUI 驗證 Project view；editor tab 註冊由 plugin.xml 及相容性檢查覆蓋，未另外操作該選單。

完整 `./gradlew build --no-build-cache`：BUILD SUCCESSFUL in 31s，289 項測試、0 failures/errors/skipped。

`verifyPlugin`：BUILD SUCCESSFUL in 3m 10s，2025.2.6.1、2025.3.4、2026.1、2026.2.2 全部 Compatible；仍為既有 4 處 Experimental API 提醒，本次未新增。
