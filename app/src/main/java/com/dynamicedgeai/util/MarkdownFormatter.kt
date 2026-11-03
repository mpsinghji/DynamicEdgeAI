package com.dynamicedgeai.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.util.regex.Pattern

/**
 * Parses markdown-formatted AI responses into rich Android Spannable text.
 * Replaces raw markdown syntax (*, **, ***, #) with native bold, italic,
 * bullet lists, and header formatting.
 */
object MarkdownFormatter {

    private val BOLD_ITALIC_PATTERN = Pattern.compile("(\\*\\*\\*|___)(?!\\s)(.+?)(?<!\\s)\\1")
    private val BOLD_PATTERN = Pattern.compile("(\\*\\*|__)(?!\\s)(.+?)(?<!\\s)\\1")
    private val ITALIC_PATTERN = Pattern.compile("(?<!\\w)(\\*|_)(?!\\s)(.+?)(?<!\\s)\\1(?!\\w)")
    private val CODE_PATTERN = Pattern.compile("`([^`]+)`")

    fun format(rawText: String?, context: Context? = null): CharSequence {
        if (rawText.isNullOrBlank()) return ""

        // Phase 1: Pre-process line-by-line structure (bullet points and headers)
        val lines = rawText.split("\n")
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                // Bullet points: convert "* " or "- " to clean "• "
                trimmed.startsWith("* ") -> {
                    val indent = line.substring(0, line.indexOf(trimmed))
                    processedLines.add("$indent• ${trimmed.substring(2)}")
                }
                trimmed.startsWith("- ") -> {
                    val indent = line.substring(0, line.indexOf(trimmed))
                    processedLines.add("$indent• ${trimmed.substring(2)}")
                }
                trimmed.startsWith("+ ") -> {
                    val indent = line.substring(0, line.indexOf(trimmed))
                    processedLines.add("$indent• ${trimmed.substring(2)}")
                }
                // Headers: strip ### and keep title text
                trimmed.startsWith("### ") -> {
                    processedLines.add(trimmed.substring(4))
                }
                trimmed.startsWith("## ") -> {
                    processedLines.add(trimmed.substring(3))
                }
                trimmed.startsWith("# ") -> {
                    processedLines.add(trimmed.substring(2))
                }
                else -> {
                    processedLines.add(line)
                }
            }
        }

        val textWithBullets = processedLines.joinToString("\n")
        val builder = SpannableStringBuilder()

        // Phase 2: Process inline formatting (Bold, Italic, Code)
        builder.append(textWithBullets)

        // 1. Bold + Italic: ***text***
        applyRegexSpan(builder, BOLD_ITALIC_PATTERN) {
            StyleSpan(Typeface.BOLD_ITALIC)
        }

        // 2. Bold: **text**
        applyRegexSpan(builder, BOLD_PATTERN) {
            StyleSpan(Typeface.BOLD)
        }

        // 3. Italic: *text*
        applyRegexSpan(builder, ITALIC_PATTERN) {
            StyleSpan(Typeface.ITALIC)
        }

        // 4. Inline code: `code`
        applyRegexSpan(builder, CODE_PATTERN) {
            TypefaceSpan("monospace")
        }

        // Clean up any remaining orphan double or triple asterisks left behind
        var orphanMatcher = Pattern.compile("(\\*\\*\\*|\\*\\*)").matcher(builder)
        while (orphanMatcher.find()) {
            val start = orphanMatcher.start()
            val end = orphanMatcher.end()
            builder.delete(start, end)
            orphanMatcher = Pattern.compile("(\\*\\*\\*|\\*\\*)").matcher(builder)
        }

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
            val innerText = matcher.group(if (matcher.groupCount() >= 2) 2 else 1) ?: ""

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
}

