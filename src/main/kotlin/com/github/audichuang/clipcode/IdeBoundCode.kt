package com.github.audichuang.clipcode

/**
 * 標記僅在 IntelliJ runtime 環境下才有意義的程式碼路徑。
 *
 * 套用於：modal dialog 顯示、ProgressManager 背景 Task body、
 * EDT invokeLater 內的 UI 操作、IntelliJ data-context selection 收集、
 * 直接呼叫 git binary 或 PSI decompile 的 wrapper。
 *
 * 這些路徑無法在純 unit test (BasePlatformTestCase) 環境穩定執行；
 * Kover 配置以此 annotation 排除覆蓋率計算，使覆蓋率反映「邏輯可測表面」。
 *
 * 不要把純邏輯 helper 標上這個 annotation。
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.BINARY)
internal annotation class IdeBoundCode
