# Mac IntelliJ Git 卡頓：實機日誌分析（2026-09-08）

## 結論

透過 SSH mac 複製使用者指定的 idea.log 與現存 freeze thread dumps。現有證據沒有指向 ClipCode 正在阻塞 UI；明確捕捉到原生 Git graph 計算與大量 macOS Metal 繪圖等待。不能僅憑一次快照排除插件間接影響，也不能宣稱卡頓已修好。

## 實際載入版本

idea.log 的 7 筆 Loaded custom plugins 紀錄均列 ClipCode **1.1.9**、GifSprite **1.0.1**，包括本次複製中最新一筆（第 56906 行）。使用者 Mac 的運行版本與本次 Linux 驗證的修改版 1.2.5 不同。更新 Git 原始碼不會自動更新已安裝 IDE 插件。

## 凍結證據

- 共取得 45 個 freeze 事件的 45 份 thread dump，涵蓋 2026-09-02 至 09-07，包含舊 build 262.9437.185 與目前 262.10315.125。idea.log 本身列出 42 筆 UI was frozen。
- 27/45 份的 EDT 堆疊包含 MTLRenderQueue，其中 26 份 EDT 狀態是 WAITING。觀察到 EDT 等待 Java2D Queue Flusher，後者停留在原生 MTLRenderQueue.flushBuffer。這支持繪圖管線是重要排查方向，不代表已證明 GPU／JBR bug 或單一插件根因。
- 2026-09-07 07:53:17 的日誌記錄凍結 **13,514 ms**。對應 threadDump-20260907-075317.txt 的 EDT 為 RUNNABLE：

```text
CollapsedGraph$CompiledGraph.getAdjacentEdges
PrintElementGeneratorImpl.getPrintElements
VisibleGraphImpl$RowInfoImpl.getPrintElements
GraphTableModel.getPrintElements / getValueAt
VcsLogGraphTable.uiDataSnapshot
PreCachedDataContext
ActionToolbarImpl.updateActionsImpl
ToolbarUpdater / ActionManagerImpl$MyTimer
```

此處是平台定時更新 toolbar 的 data context 時進入 Git 線圖運算，堆疊沒有 ClipCode action。單一樣本不表示整段 13.5 秒都花在同一函式。

- 45 份快照均未發現 `at com.github.audichuang.clipcode...` 執行堆疊。部分 coroutine dump 的 ClipCode 名称是已建立的 component scope，不能當作正在執行或占用 CPU 的證據。
- 2026-09-05 10:30:08（7,433 ms）、09-06 23:08:55 所報的 23:07:31 事件（5,235 ms），EDT 出現 `GifSpriteStickerService.startAutoPlay` 第 751 行，經 Swing Timer 呼叫 SwingUtilities.invokeLater。它是應做停用動畫對照的候選，不足以由兩張快照認定 GifSprite 導致所有卡頓。
- 09-07 22:44 的一張快照 EDT 已在 EventQueue.getNextEvent 等待事件，顯示取樣可能落在恢復之後；不能把全部 freeze 名稱視為同一根因。

## 其他 ClipCode 訊息

09-07 09:45:33 的舊版 CopyGitFilesContentAction 記錄 IOException：將 directory 當檔案讀取。這是一次複製正確性警告，沒有證據將它連到 07:53 的 Git graph 凍結。

## 下一步與界線

優先在同一 Mac、同一 repository、相同 Git Log 操作記錄 CPU profile，並分別對照暫停 GifSprite 動畫、停用 ClipCode；一次改一個因素。只有對照後才能判定因果。若需要變更渲染設定，應另做可回復的獨立測試；本次沒有修改 Mac IDE 設定、停用插件或重啟 IDE。

本次 Linux 修改版 1.2.5 的 266 項測試與四個 IDE 版本 Plugin Verifier 均通過，且已真實操作 Git copy、PR copy、一般 copy 與 restore。詳見同目錄 linux-runtime-and-git-log 報告；這些結果不替代 Mac 卡頓重現。

原始資料僅存本機 `/tmp/clipcode-mac-audit/`（目錄權限 0700），不提交可能含私人專案資料的原始 log。idea.log 是複製當刻的快照，IDE 後續仍可繼續寫入。
