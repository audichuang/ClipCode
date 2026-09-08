package com.github.audichuang.clipcode

/**
 * The four numbers the copy notification reports for a payload, computed in ONE
 * linear pass so the user sees how large a chunk they're about to paste into an AI
 * assistant.
 *
 * Byte-mirror of `ClipCodeVSCode/src/copy.ts payloadStats`. All four are a function
 * of the whole clipboard text — headers, pre/post text and the `clipcode-root` line
 * included — because that is what actually gets pasted. They are deliberately NOT
 * per-file sums: the old per-file counters silently dropped every header and blank
 * separator line, so `Total characters` disagreed with the payload it described and
 * with the VS Code sibling. Pinned by the shared golden `clipboard-contract.json`
 * (`tokenCases`) on both sides.
 *
 * - [Stats.chars]  UTF-16 code units, i.e. `String.length` on both sides. An astral
 *                  char (emoji) counts 2 here and 2 in JS — identical by construction.
 * - [Stats.lines]  `\n` count + 1, so a payload with no trailing newline still counts
 *                  its last line. Empty text is 0 lines.
 * - [Stats.words]  maximal runs of non-separator characters.
 * - [Stats.tokens] [Stats.words] plus a point for each structural punctuation mark —
 *                  a crude AI-payload size gauge, deliberately not a real tokenizer.
 *
 * The separator set is spelled out as ASCII rather than `\s`: Java's `\s` already IS
 * these six characters, but JS `\s` is Unicode-wide, so the TS mirror must pin the
 * ASCII set explicitly — writing it out here keeps the two scans visibly identical.
 *
 * One linear pass, O(1) extra memory. The notification entry point runs this on a
 * background thread: even a linear scan can stall the EDT for a large payload.
 */
object TokenEstimator {
    /** Mirrors ClipCodeVSCode/src/notify.ts. Copy toasts turn yellow at WARN, red at DANGER. */
    const val WARN_THRESHOLD = 1_000_000
    const val DANGER_THRESHOLD = 2_000_000

    data class Stats(val chars: Int, val lines: Int, val words: Int, val tokens: Int)

    fun stats(text: String): Stats {
        var words = 0
        var punctuation = 0
        var newlines = 0
        var inWord = false
        for (i in text.indices) {
            val code = text[i].code
            if (code == 0x0A) newlines++
            // Space 0x20 plus the contiguous 0x09..0x0D block (tab, LF, VT, FF, CR) —
            // exactly the six ASCII separators, nothing Unicode.
            if (code == 0x20 || code in 0x09..0x0D) {
                inWord = false
                continue
            }
            // A maximal run of non-separators is one word — what split-then-drop-empty counted.
            if (!inWord) {
                inWord = true
                words++
            }
            // ; , ( ) { } [ ]
            if (code == 0x3B || code == 0x2C || code == 0x28 || code == 0x29 ||
                code == 0x7B || code == 0x7D || code == 0x5B || code == 0x5D
            ) {
                punctuation++
            }
        }
        return Stats(
            chars = text.length,
            lines = if (text.isEmpty()) 0 else newlines + 1,
            words = words,
            tokens = words + punctuation
        )
    }

    /** The [Stats.tokens] field alone. Kept because it is the name the contract fixtures use. */
    fun estimate(text: String): Int = stats(text).tokens
}
