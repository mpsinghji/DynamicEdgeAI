package com.dynamicedgeai.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.util.regex.Pattern

/**
 * Parses markdown-formatted AI responses into rich Android Spannable text.
 * Replaces raw markdown syntax (*, **, ***, #) with native bold, italic,
 * bullet lists, and header formatting.
 * Eliminates all raw asterisks so the user receives clean, typography-rich text.
 */
object MarkdownFormatter {

    private val BOLD_ITALIC_PATTERN = Pattern.compile("(\\*\\*\\*|___)(.+?)\\1")
    private val BOLD_PATTERN = Pattern.compile("(\\*\\*|__)(.+?)\\1")
    private val ITALIC_PATTERN = Pattern.compile("(?<![a-zA-Z0-9])(\\*|_)(.+?)\\1(?![a-zA-Z0-9])")
    private val CODE_PATTERN = Pattern.compile("`([^`]+)`")
    private val BULLET_PATTERN = Regex("""^(\s*)([*+\-])\s+(.*)$""")

    fun format(rawText: String?, context: Context? = null): CharSequence {
        if (rawText.isNullOrBlank()) return ""

        // Phase 1: Pre-process line-by-line structure (bullet points and headers)
        val lines = rawText.split("\n")
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            val bulletMatch = BULLET_PATTERN.find(line)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1]
                val content = bulletMatch.groupValues[3]
                processedLines.add("$indent• $content")
                continue
            }

            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("### ") -> processedLines.add(trimmed.substring(4))
                trimmed.startsWith("## ") -> processedLines.add(trimmed.substring(3))
                trimmed.startsWith("# ") -> processedLines.add(trimmed.substring(2))
                else -> processedLines.add(line)
            }
        }

        val textWithBullets = processedLines.joinToString("\n")
        val builder = SpannableStringBuilder(textWithBullets)

        // Phase 2: Process inline formatting (Bold, Italic, Code)
        // 1. Bold + Italic: ***text***
        applyRegexSpan(builder, BOLD_ITALIC_PATTERN) {
            StyleSpan(Typeface.BOLD_ITALIC)
        }

        // 2. Bold: **text**
        applyRegexSpan(builder, BOLD_PATTERN) {
            StyleSpan(Typeface.BOLD)
        }

        // 3. Italic: *text* or _text_
        applyRegexSpan(builder, ITALIC_PATTERN) {
            StyleSpan(Typeface.ITALIC)
        }

        // 4. Inline code: `code`
        applyRegexSpan(builder, CODE_PATTERN) {
            TypefaceSpan("monospace")
        }

        // Phase 3: Final sweep - remove ANY stray asterisks (*, **, ***) that failed to match
        // so raw stars never appear in prompt responses!
        cleanStrayAsterisks(builder)

        return builder
    }

    private fun applyRegexSpan(
        builder: SpannableStringBuilder,
        pattern: Pattern,
        spanFactory: () -> Any
    ) {
        var matcher = pattern.matcher(builder)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val innerText = matcher.group(2) ?: ""

            builder.replace(start, end, innerText)
            builder.setSpan(
                spanFactory(),
                start,
                start + innerText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Re-match since the buffer length changed
            matcher = pattern.matcher(builder)
        }
    }

    private fun cleanStrayAsterisks(builder: SpannableStringBuilder) {
        var starMatcher = Pattern.compile("\\*+").matcher(builder)
        while (starMatcher.find()) {
            val start = starMatcher.start()
            val end = starMatcher.end()
            builder.delete(start, end)
            starMatcher = Pattern.compile("\\*+").matcher(builder)
        }
    }
}
