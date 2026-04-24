package com.github.audichuang.clipcode

object GitClipboardFormatter {
    fun buildHeader(
        headerFormat: String,
        clipboardPath: String,
        changeType: ChangeTypeLabel?
    ): String {
        val pathWithLabel = if (changeType != null) {
            "${changeType.label} $clipboardPath"
        } else {
            clipboardPath
        }
        return headerFormat.replace("\$FILE_PATH", pathWithLabel)
    }
}
