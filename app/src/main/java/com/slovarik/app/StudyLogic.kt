package com.slovarik.app

import kotlin.math.max
import kotlin.math.roundToLong

data class Review(val repetitions: Int = 0, val intervalDays: Int = 0, val ease: Double = 2.5, val due: Long = 0L)

/** SM-2 with grades 0..5. Grades <3 reset the learning streak. */
fun schedule(review: Review, grade: Int, now: Long): Review {
    require(grade in 0..5)
    val ease = max(1.3, review.ease + (0.1 - (5 - grade) * (0.08 + (5 - grade) * 0.02)))
    val reps = if (grade < 3) 0 else review.repetitions + 1
    val interval = when {
        grade < 3 -> 1
        reps == 1 -> 1
        reps == 2 -> 6
        else -> max(1, (review.intervalDays * ease).roundToLong().toInt())
    }
    return Review(reps, interval, ease, now + interval * 86_400_000L)
}

data class WordPair(val word: String, val translation: String)

/** Accept a pasted list or CSV/TSV export. Quoted CSV fields and escaped double quotes are supported. */
fun parsePairs(text: String): List<WordPair> = text.lineSequence().mapNotNull { line ->
    val clean = line.trimStart('\uFEFF')
    val cells = parseDelimited(clean, detectDelimiter(clean))
    val word = cells.getOrNull(0)?.trim().orEmpty()
    val translation = cells.getOrNull(1)?.trim().orEmpty()
    if (word.isBlank() || translation.isBlank() ||
        (word.equals("word", true) && translation.equals("translation", true))) null
    else WordPair(word, translation)
}.toList()

/** Separators within quoted CSV fields are part of the value, not the dialect. */
private fun detectDelimiter(line: String): Char {
    val outside = mutableSetOf<Char>()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        if (char == '"') {
            if (quoted && index + 1 < line.length && line[index + 1] == '"') index++
            else quoted = !quoted
        } else if (!quoted && char in listOf('\t', ';', ',')) outside += char
        index++
    }
    return when { '\t' in outside -> '\t'; ';' in outside -> ';'; else -> ',' }
}

private fun parseDelimited(line: String, delimiter: Char): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { field.append('"'); index++ }
            char == '"' -> quoted = !quoted
            char == delimiter && !quoted -> { fields += field.toString(); field.clear() }
            else -> field.append(char)
        }
        index++
    }
    if (quoted) return emptyList()
    fields += field.toString()
    return fields
}

fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""
