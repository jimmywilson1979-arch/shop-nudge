package com.jimmywilson.shopnudge

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reads the text in a screenshot (on the phone, nothing uploaded) and guesses
 * which bits are most likely the product name.
 */
object ScreenshotReader {

    fun read(ctx: Context, uri: Uri, onResult: (List<String>) -> Unit, onError: (String) -> Unit) {
        val image = try {
            InputImage.fromFilePath(ctx, uri)
        } catch (e: Exception) {
            onError("Couldn't open that picture")
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                onResult(candidates(text.textBlocks.map { b -> b.lines.map { it.text } }))
                recognizer.close()
            }
            .addOnFailureListener {
                onError("Couldn't read any words in that picture")
                recognizer.close()
            }
    }

    /** Join wrapped lines: "On-" + "The-Go Squash" -> "On-The-Go Squash". */
    fun joinLines(lines: List<String>): String = lines.fold("") { acc, raw ->
        val l = raw.trim()
        when {
            acc.isEmpty() -> l
            acc.endsWith("-") -> acc + l
            else -> "$acc $l"
        }
    }

    private val junk = Regex(
        "(?i)^(add|add to (trolley|basket|bag|cart)|basket|trolley|cart|bag|reviews?|write a review|search|" +
            "favourites?|offers?|delivery|collection|in stock|out of stock|sign in|log in|home|menu|share|" +
            "buy now|shop now|details|product details|ingredients|nutrition|description|quantity|qty|" +
            "see more|view all|back|close|next|previous|more|less|save|saved|filter|sort|" +
            "clubcard price|nectar price|aldi price match|no added sugar|new|sale)$"
    )
    private val price = Regex("^[£$€]?\\s?\\d+([.,]\\d{1,2})?\\s?(p|each|/kg|/l|per.*)?$", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b\\d{1,2}:\\d{2}\\b")
    private val web = Regex("(?i)(https?://|www\\.|\\.com\\b|\\.co\\.uk\\b)")

    fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim('-', '|', '·', '•', ':', ',', '"', '*', ' ')

    fun usable(s: String): Boolean {
        if (s.length !in 3..90) return false
        if (s.count { it.isLetter() } < 3) return false
        if (junk.matches(s) || price.matches(s) || clock.containsMatchIn(s) || web.containsMatchIn(s)) return false
        if (s.startsWith("£") || s.contains("£") && s.split(" ").size <= 3) return false
        return true
    }

    fun score(s: String): Double {
        val words = s.split(" ").filter { it.isNotBlank() }.size
        var sc = 0.0
        if (words in 2..12) sc += 3 else if (words == 1) sc -= 1 else sc -= 2
        val hasLower = s.any { it.isLowerCase() }
        val hasUpper = s.any { it.isUpperCase() }
        if (hasLower && hasUpper) sc += 2          // Product Titles Look Like This
        if (!hasLower) sc -= 1                     // ALL CAPS is usually packaging / buttons
        sc += minOf(words, 8) * 0.3                // longer titles beat single words
        if (Regex("\\d+\\s?(ml|l|g|kg|pack|x\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) && words >= 3) sc += 0.5
        if (s.endsWith(".") && words > 8) sc -= 3  // sentences / descriptions
        return sc
    }

    /** Most likely product names first. */
    fun candidates(blocks: List<List<String>>): List<String> {
        val raw = LinkedHashSet<String>()
        blocks.forEach { lines ->
            if (lines.size in 2..3) raw += joinLines(lines)   // a title wrapped over a few lines
            else lines.forEach { raw += it }
        }
        // A title can get split into separate chunks ("...On-" / "The-Go Squash"): stitch them back.
        val all = blocks.flatten().map { it.trim() }
        all.forEachIndexed { i, l ->
            if (l.endsWith("-") && i + 1 < all.size) {
                raw += joinLines(listOf(l, all[i + 1]))
                raw.remove(l)
            }
        }
        return raw.map { clean(it) }
            .filter { usable(it) }
            .distinctBy { it.lowercase() }
            .sortedByDescending { score(it) }
            .take(8)
    }
}
