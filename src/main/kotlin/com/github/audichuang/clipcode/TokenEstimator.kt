package com.github.audichuang.clipcode

/**
 * Rough token estimate for a copied payload, shown in the copy notification so the
 * user sees how large a chunk they're about to paste into an AI assistant: word count
 * plus a few structural punctuation marks — deliberately crude, not a real tokenizer.
 *
 * Byte-mirror of `ClipCodeVSCode/src/copy.ts estimateTokens`. Both sides count the
 * SAME thing (the full payload, not per-file content) with the SAME whitespace class,
 * so the two tools report identical numbers for identical clipboard text. Pinned by
 * the shared golden `clipboard-contract.json` (`tokenCases`) on both sides.
 *
 * The separator set is spelled out as ASCII rather than `\s`: Java's `\s` already IS
 * these six characters, but JS `\s` is Unicode-wide, so the TS mirror must pin the
 * ASCII set explicitly — writing it out here keeps the two scans visibly identical.
 *
 * One linear pass, O(1) extra memory: this runs on the EDT for the whole payload, and
 * splitting a multi-megabyte copy into one String per word froze the IDE precisely when
 * the estimate matters most (the 1M/2M-token warning).
 */
object TokenEstimator {
    /** Mirrors ClipCodeVSCode/src/notify.ts. Copy toasts turn yellow at WARN, red at DANGER. */
    const val WARN_THRESHOLD = 1_000_000
    const val DANGER_THRESHOLD = 2_000_000

    fun estimate(text: String): Int {
        var tokens = 0
        var inWord = false
        for (i in text.indices) {
            val code = text[i].code
            // Space 0x20 plus the contiguous 0x09..0x0D block (\t \n \u000B \u000C \r) —
            // exactly the six ASCII separators, nothing Unicode.
            if (code == 0x20 || code in 0x09..0x0D) {
                inWord = false
                continue
            }
            // A maximal run of non-separators is one word — what split-then-drop-empty counted.
            if (!inWord) {
                inWord = true
                tokens++
            }
            // ; , ( ) { } [ ]
            if (code == 0x3B || code == 0x2C || code == 0x28 || code == 0x29 ||
                code == 0x7B || code == 0x7D || code == 0x5B || code == 0x5D
            ) {
                tokens++
            }
        }
        return tokens
    }
}
