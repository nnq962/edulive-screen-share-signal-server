package com.codewithkael.webrtcscreenshare.service.keyboard

/**
 * TextHelper class handles all text conversion and Telex input logic
 * Extracted from KeyboardService to reduce LOC and improve maintainability
 */
object TextHelper {
    
    // Tone key mapping for Vietnamese Telex input
    private val toneKeyMap = mapOf(
        's' to 1,
        'f' to 2,
        'r' to 3,
        'x' to 4,
        'j' to 5
    )

    // Vowel mapping tables for Vietnamese characters
    private val vowelDecodeMap = HashMap<Char, VowelInfo>()
    private val vowelEncodeMap = HashMap<VowelKey, Char>()

    // Vietnamese vowel sets with their variants
    private val vowelSets = listOf(
        VowelSet(
            base = 'a',
            lowercaseForms = listOf("aáàảãạ", "âấầẩẫậ", "ăắằẳẵặ"),
            uppercaseForms = listOf("AÁÀẢÃẠ", "ÂẤẦẨẪẬ", "ĂẮẰẲẴẶ")
        ),
        VowelSet(
            base = 'e',
            lowercaseForms = listOf("eéèẻẽẹ", "êếềểễệ"),
            uppercaseForms = listOf("EÉÈẺẼẸ", "ÊẾỀỂỄỆ")
        ),
        VowelSet(
            base = 'i',
            lowercaseForms = listOf("iíìỉĩị"),
            uppercaseForms = listOf("IÍÌỈĨỊ")
        ),
        VowelSet(
            base = 'o',
            lowercaseForms = listOf("oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ"),
            uppercaseForms = listOf("OÓÒỎÕỌ", "ÔỐỒỔỖỘ", "ƠỚỜỞỠỢ")
        ),
        VowelSet(
            base = 'u',
            lowercaseForms = listOf("uúùủũụ", "ưứừửữự"),
            uppercaseForms = listOf("UÚÙỦŨỤ", "ƯỨỪỬỮỰ")
        ),
        VowelSet(
            base = 'y',
            lowercaseForms = listOf("yýỳỷỹỵ"),
            uppercaseForms = listOf("YÝỲỶỸỴ")
        )
    )

    /**
     * Main function to apply Telex input transformation
     * @param existing The existing text
     * @param input The new input to be processed
     * @return The transformed text with Telex rules applied
     */
    fun applyTelexInput(existing: String, input: String): String {
        ensureVowelTables()
        if (input.isEmpty()) return existing
        if (input.length > 1) {
            // Treat multi-character payloads (e.g., paste) as literal text
            return existing + input
        }
        var result = existing
        input.forEach { ch ->
            result = applyTelexChar(result, ch)
        }
        return result
    }

    /**
     * Apply Telex transformation for a single character
     * @param existing The existing text
     * @param char The character to process
     * @return The transformed text
     */
    private fun applyTelexChar(existing: String, char: Char): String {
        ensureVowelTables()
        val lower = char.lowercaseChar()
        val tone = toneKeyMap[lower]
        
        // Handle tone marks (s, f, r, x, j)
        if (tone != null) {
            val index = findToneTarget(existing)
            if (index != -1) {
                val info = vowelDecodeMap[existing[index]]
                if (info != null) {
                    val replacement = encodeVowel(
                        info.base,
                        info.type,
                        tone,
                        info.uppercase
                    )
                    if (replacement != null) {
                        return replaceChar(existing, index, replacement)
                    }
                }
            }
            return existing + char
        }

        // Handle tone removal (z)
        if (lower == 'z') {
            val index = findToneTarget(existing)
            if (index != -1) {
                val info = vowelDecodeMap[existing[index]]
                if (info != null && info.tone != 0) {
                    val replacement = encodeVowel(
                        info.base,
                        info.type,
                        0,
                        info.uppercase
                    )
                    if (replacement != null) {
                        return replaceChar(existing, index, replacement)
                    }
                }
            }
            return existing
        }

        // Handle 'd' -> 'đ' transformation
        if (lower == 'd') {
            val lastIndex = existing.lastIndex
            if (lastIndex >= 0) {
                val lastChar = existing[lastIndex]
                if (lastChar == 'd' || lastChar == 'D') {
                    val replacement = if (lastChar.isUpperCase()) 'Đ' else 'đ'
                    return replaceChar(existing, lastIndex, replacement)
                }
            }
            return existing + char
        }

        // Handle 'w' for vowel type changes (a->ă, o->ơ, u->ư)
        if (lower == 'w') {
            val index = findToneTarget(existing)
            if (index != -1) {
                val info = vowelDecodeMap[existing[index]]
                if (info != null) {
                    val newType = when (info.base) {
                        'a' -> 2
                        'o' -> 2
                        'u' -> 1
                        else -> info.type
                    }
                    if (newType != info.type) {
                        val replacement = encodeVowel(
                            info.base,
                            newType,
                            info.tone,
                            info.uppercase
                        )
                        if (replacement != null) {
                            return replaceChar(existing, index, replacement)
                        }
                    }
                }
            }
            return existing + char
        }

        // Handle vowel duplication (a->â, e->ê, o->ô)
        if (lower == 'a' || lower == 'e' || lower == 'o') {
            val lastIndex = existing.lastIndex
            if (lastIndex >= 0) {
                val info = vowelDecodeMap[existing[lastIndex]]
                if (info != null && info.base == lower && info.type == 0) {
                    val targetType = when (lower) {
                        'a' -> 1
                        'e' -> 1
                        'o' -> 1
                        else -> info.type
                    }
                    if (targetType != info.type) {
                        val replacement = encodeVowel(
                            info.base,
                            targetType,
                            info.tone,
                            info.uppercase
                        )
                        if (replacement != null) {
                            return replaceChar(existing, lastIndex, replacement)
                        }
                    }
                }
            }
        }

        return existing + char
    }

    /**
     * Find the target vowel for tone application in the current word
     * @param text The text to search in
     * @return The index of the target vowel, or -1 if not found
     */
    private fun findToneTarget(text: String): Int {
        ensureVowelTables()

        // Find the last space to get the current word boundary
        val lastSpaceIndex = text.lastIndexOf(' ')
        val startIndex = if (lastSpaceIndex == -1) 0 else lastSpaceIndex + 1

        // Only search in the current word (after the last space)
        for (index in text.length - 1 downTo startIndex) {
            if (vowelDecodeMap.containsKey(text[index])) {
                return index
            }
        }
        return -1
    }

    /**
     * Encode a vowel with specific base, type, tone and case
     * @param base The base vowel character
     * @param type The vowel type (0=normal, 1=circumflex, 2=breve)
     * @param tone The tone (0=none, 1=acute, 2=grave, 3=hook, 4=tilde, 5=dot)
     * @param uppercase Whether the result should be uppercase
     * @return The encoded vowel character, or null if not found
     */
    private fun encodeVowel(base: Char, type: Int, tone: Int, uppercase: Boolean): Char? {
        ensureVowelTables()
        return vowelEncodeMap[VowelKey(base, type, tone, uppercase)]
    }

    /**
     * Replace a character at a specific index in a string
     * @param text The original text
     * @param index The index to replace at
     * @param replacement The replacement character
     * @return The modified string
     */
    private fun replaceChar(text: String, index: Int, replacement: Char): String {
        if (index < 0 || index >= text.length) return text
        val builder = StringBuilder(text)
        builder.setCharAt(index, replacement)
        return builder.toString()
    }

    /**
     * Initialize the vowel mapping tables if not already done
     */
    private fun ensureVowelTables() {
        if (vowelDecodeMap.isNotEmpty()) return

        vowelSets.forEach { set ->
            set.lowercaseForms.forEachIndexed { type, tones ->
                tones.forEachIndexed { tone, ch ->
                    vowelDecodeMap[ch] = VowelInfo(set.base, type, tone, false)
                    vowelEncodeMap[VowelKey(set.base, type, tone, false)] = ch
                }
            }
            set.uppercaseForms.forEachIndexed { type, tones ->
                tones.forEachIndexed { tone, ch ->
                    vowelDecodeMap[ch] = VowelInfo(set.base, type, tone, true)
                    vowelEncodeMap[VowelKey(set.base, type, tone, true)] = ch
                }
            }
        }
    }

    /**
     * Sanitize original text by removing hint text
     * @param original The original text
     * @param hint The hint text to remove
     * @return The sanitized text
     */
    fun sanitizeOriginalText(original: String?, hint: String?): String {
        val value = original ?: ""
        if (value.isEmpty()) return ""
        val hintValue = hint ?: return value
        return if (value == hintValue) "" else value
    }
}

/**
 * Data class representing Vietnamese vowel information
 */
data class VowelInfo(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

/**
 * Data class used as key for vowel encoding map
 */
data class VowelKey(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

/**
 * Data class representing a set of Vietnamese vowel forms
 */
data class VowelSet(
    val base: Char,
    val lowercaseForms: List<String>,
    val uppercaseForms: List<String>
)

