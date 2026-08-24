package dev.chirpboard.app.feature.keyboard.service

/**
 * Grapheme-cluster boundary helpers approximating UAX #29 extended grapheme clusters (IME-8).
 *
 * Backspace and the space-bar cursor drag must operate on whole user-perceived characters: one
 * press after "🇺🇸", "👍🏽", "👨‍👩‍👧‍👦" or "❤️" removes the entire glyph instead of shredding it into
 * regional-indicator halves, orphan ZWJ fragments or a restyled base character.
 *
 * Implemented as a pure-Kotlin pairwise scanner (rather than `android.icu.text.BreakIterator`) so
 * the exact same logic runs on-device and in plain JVM unit tests. Covered rules: CRLF, control
 * boundaries, Hangul jamo composition, Extend/ZWJ/SpacingMark joining (combining marks, variation
 * selectors, skin-tone modifiers, keycaps), emoji ZWJ sequences (GB11, with an emoji-range
 * approximation of Extended_Pictographic) and regional-indicator pairing (GB12/13). The rare
 * Prepend rule (GB9b) is intentionally omitted.
 */
internal object GraphemeBoundaries {
    private const val ZWJ = 0x200D
    private const val ZWNJ = 0x200C

    /** Tag characters (UAX #29 Extend) that spell out emoji tag sequences such as regional flags. */
    private const val TAG_FIRST = 0xE0020
    private const val TAG_LAST = 0xE007F
    private const val REGIONAL_INDICATOR_FIRST = 0x1F1E6
    private const val REGIONAL_INDICATOR_LAST = 0x1F1FF
    private const val EMOJI_MODIFIER_FIRST = 0x1F3FB
    private const val EMOJI_MODIFIER_LAST = 0x1F3FF

    private const val HANGUL_L_FIRST = 0x1100
    private const val HANGUL_L_LAST = 0x115F
    private const val HANGUL_L_EXT_FIRST = 0xA960
    private const val HANGUL_L_EXT_LAST = 0xA97C
    private const val HANGUL_V_FIRST = 0x1160
    private const val HANGUL_V_LAST = 0x11A7
    private const val HANGUL_V_EXT_FIRST = 0xD7B0
    private const val HANGUL_V_EXT_LAST = 0xD7C6
    private const val HANGUL_T_FIRST = 0x11A8
    private const val HANGUL_T_LAST = 0x11FF
    private const val HANGUL_T_EXT_FIRST = 0xD7CB
    private const val HANGUL_T_EXT_LAST = 0xD7FB
    private const val HANGUL_SYLLABLE_FIRST = 0xAC00
    private const val HANGUL_SYLLABLE_LAST = 0xD7A3
    private const val HANGUL_T_COUNT = 28

    /**
     * How far back the scanner anchors before the position of interest. Clusters longer than this
     * (hundreds of UTF-16 units) are pathological; a misaligned anchor re-synchronizes within one
     * real cluster anyway.
     */
    private const val SCAN_LOOKBACK_UNITS = 256

    /** The largest boundary strictly before [index] (so `previousBoundary(text, length)` is the start of the last cluster). */
    fun previousBoundary(
        text: CharSequence,
        index: Int,
    ): Int {
        val end = index.coerceIn(0, text.length)
        if (end <= 0) return 0
        val anchor = scanAnchor(text, end)
        var lastBoundary = anchor
        scanBoundaries(text, anchor, end) { boundary ->
            if (boundary < end) {
                lastBoundary = boundary
            }
            boundary < end
        }
        return lastBoundary
    }

    /** The smallest boundary strictly after [index] (clamped to `text.length`). */
    fun nextBoundary(
        text: CharSequence,
        index: Int,
    ): Int {
        if (index >= text.length) return text.length
        val start = index.coerceAtLeast(0)
        val anchor = scanAnchor(text, start + 1)
        var result = text.length
        scanBoundaries(text, anchor, text.length) { boundary ->
            if (boundary > start) {
                result = boundary
                false
            } else {
                true
            }
        }
        return result
    }

    /** UTF-16 length of the grapheme cluster ending at the end of [before] (0 for empty input). */
    fun trailingClusterLength(before: CharSequence): Int = before.length - previousBoundary(before, before.length)

    private fun scanAnchor(
        text: CharSequence,
        end: Int,
    ): Int {
        var anchor = (end - SCAN_LOOKBACK_UNITS).coerceAtLeast(0)
        if (anchor > 0 && Character.isLowSurrogate(text[anchor]) && Character.isHighSurrogate(text[anchor - 1])) {
            anchor--
        }
        return anchor
    }

    /**
     * Streams every boundary position in `[start], (start..end)` to [onBoundary] in order;
     * [onBoundary] returns false to stop early. The position [start] itself is treated as a
     * boundary by construction (see [scanAnchor]).
     */
    private inline fun scanBoundaries(
        text: CharSequence,
        start: Int,
        end: Int,
        onBoundary: (Int) -> Boolean,
    ) {
        var index = start
        var previousCp = -1
        // Number of consecutive regional indicators ending at the previous code point.
        var regionalIndicatorRun = 0
        // True while the scanner is inside "ExtPict Extend*" (GB11's left-hand context).
        var inPictographicSequence = false
        // True when the previous code point is a ZWJ extending a pictographic sequence.
        var pictographicZwjPending = false

        while (index < end) {
            val cp = Character.codePointAt(text, index)
            val boundaryHere =
                if (previousCp == -1) {
                    true
                } else {
                    isBreak(previousCp, cp, regionalIndicatorRun, pictographicZwjPending)
                }
            if (boundaryHere && !onBoundary(index)) {
                return
            }

            regionalIndicatorRun = if (isRegionalIndicator(cp)) regionalIndicatorRun + 1 else 0
            when {
                isExtendedPictographic(cp) -> {
                    inPictographicSequence = true
                    pictographicZwjPending = false
                }
                inPictographicSequence && !pictographicZwjPending && isExtend(cp) -> Unit
                inPictographicSequence && !pictographicZwjPending && cp == ZWJ -> pictographicZwjPending = true
                else -> {
                    inPictographicSequence = false
                    pictographicZwjPending = false
                }
            }

            previousCp = cp
            index += Character.charCount(cp)
        }
    }

    private fun isBreak(
        previous: Int,
        current: Int,
        regionalIndicatorRunBeforeCurrent: Int,
        pictographicZwjPending: Boolean,
    ): Boolean {
        // GB3: CR x LF
        if (previous == '\r'.code && current == '\n'.code) return false
        // GB4/GB5: break around controls.
        if (isControl(previous) || isControl(current)) return true
        // GB6-GB8: Hangul jamo composition.
        if (isHangulL(previous) && (isHangulL(current) || isHangulV(current) || isHangulLv(current) || isHangulLvt(current))) {
            return false
        }
        if ((isHangulLv(previous) || isHangulV(previous)) && (isHangulV(current) || isHangulT(current))) return false
        if ((isHangulLvt(previous) || isHangulT(previous)) && isHangulT(current)) return false
        // GB9/GB9a: never break before extenders, ZWJ or spacing marks.
        if (current == ZWJ || isExtend(current) || isSpacingMark(current)) return false
        // GB11: ExtPict Extend* ZWJ x ExtPict.
        if (previous == ZWJ && pictographicZwjPending && isExtendedPictographic(current)) return false
        // GB12/GB13: pair regional indicators two by two.
        if (isRegionalIndicator(previous) && isRegionalIndicator(current)) {
            return regionalIndicatorRunBeforeCurrent % 2 == 0
        }
        return true
    }

    private fun isControl(cp: Int): Boolean {
        // UAX #29 assigns ZWJ, ZWNJ and the tag characters to Extend/ZWJ, not Control, even
        // though Java types them as FORMAT. Treating them as controls broke GB9 for emoji tag
        // sequences (the Scotland flag shredded into its tag letters under backspace) and left
        // ZWNJ as its own cluster, costing an invisible extra press.
        if (cp == ZWJ || cp == ZWNJ || cp in TAG_FIRST..TAG_LAST) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL,
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            -> true

            else -> false
        }
    }

    private fun isExtend(cp: Int): Boolean {
        if (cp in EMOJI_MODIFIER_FIRST..EMOJI_MODIFIER_LAST) return true
        // Extend by property, Format by Java's character type: the tag characters that spell out
        // subdivision flags (🏴󠁧󠁢󠁳󠁣󠁴󠁿) and ZWNJ, which joins to the cluster it follows.
        if (cp == ZWNJ || cp in TAG_FIRST..TAG_LAST) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK,
            Character.ENCLOSING_MARK,
            -> true

            else -> false
        }
    }

    private fun isSpacingMark(cp: Int): Boolean = Character.getType(cp).toByte() == Character.COMBINING_SPACING_MARK

    private fun isRegionalIndicator(cp: Int): Boolean = cp in REGIONAL_INDICATOR_FIRST..REGIONAL_INDICATOR_LAST

    private fun isHangulL(cp: Int): Boolean = cp in HANGUL_L_FIRST..HANGUL_L_LAST || cp in HANGUL_L_EXT_FIRST..HANGUL_L_EXT_LAST

    private fun isHangulV(cp: Int): Boolean = cp in HANGUL_V_FIRST..HANGUL_V_LAST || cp in HANGUL_V_EXT_FIRST..HANGUL_V_EXT_LAST

    private fun isHangulT(cp: Int): Boolean = cp in HANGUL_T_FIRST..HANGUL_T_LAST || cp in HANGUL_T_EXT_FIRST..HANGUL_T_EXT_LAST

    private fun isHangulLv(cp: Int): Boolean =
        cp in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST && (cp - HANGUL_SYLLABLE_FIRST) % HANGUL_T_COUNT == 0

    private fun isHangulLvt(cp: Int): Boolean =
        cp in HANGUL_SYLLABLE_FIRST..HANGUL_SYLLABLE_LAST && (cp - HANGUL_SYLLABLE_FIRST) % HANGUL_T_COUNT != 0

    /**
     * Approximation of the Extended_Pictographic property, scoped to the ranges that occur in
     * real emoji ZWJ sequences. Only consulted for GB11 (what may follow a pictographic ZWJ).
     */
    private fun isExtendedPictographic(cp: Int): Boolean =
        when (cp) {
            0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x24C2, 0x3030, 0x303D, 0x3297, 0x3299 -> true
            else ->
                cp in 0x1F000..0x1FFFD ||
                    cp in 0x2600..0x27BF ||
                    cp in 0x2300..0x23FF ||
                    cp in 0x25A0..0x25FF ||
                    cp in 0x2B00..0x2BFF ||
                    cp in 0x2190..0x21FF ||
                    cp in 0x2900..0x297F
        }
}
