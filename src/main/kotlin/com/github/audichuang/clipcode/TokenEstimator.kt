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
 * The whitespace class is spelled out as ASCII rather than `\s`: Java's `\s` already
 * IS these six characters, but JS `\s` is Unicode-wide, so the TS mirror must pin the
 * ASCII set explicitly — writing it out here keeps the two regexes visibly identical.
 */
object TokenEstimator {
    /** Mirrors ClipCodeVSCode/src/notify.ts. Copy toasts turn yellow at WARN, red at DANGER. */
    const val WARN_THRESHOLD = 1_000_000
    const val DANGER_THRESHOLD = 2_000_000

    private val ASCII_WS = Regex("[ \\t\\n\\u000B\\u000C\\r]+")
    private val PUNCTUATION = Regex("[;{}()\\[\\],]")

    fun estimate(text: String): Int =
        text.split(ASCII_WS).count { it.isNotEmpty() } + PUNCTUATION.findAll(text).count()
}
