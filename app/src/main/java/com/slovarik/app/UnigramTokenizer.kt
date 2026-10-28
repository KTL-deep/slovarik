package com.slovarik.app

import java.text.Normalizer

/** SentencePiece-style unigram/Viterbi segmentation for the pinned OPUS vocabulary.
 * Pure Kotlin: no downloaded tokenizer code, JNI tokenizer, or network required.
 */
internal class UnigramTokenizer(
    private val pieces: List<String>,
    private val scores: DoubleArray,
    private val unknownId: Int = 1,
    private val eosId: Int = 0,
    private val padId: Int = pieces.lastIndex,
) {
    private class Node {
        val children = HashMap<Char, Node>()
        var id = -1
    }
    private val root = Node()
    private val unknownScore = (scores.minOrNull() ?: -20.0) - 10.0

    init {
        require(pieces.size == scores.size)
        pieces.forEachIndexed { id, piece ->
            if (id != unknownId && id != eosId && id != padId && piece.isNotEmpty()) {
                var node = root
                piece.forEach { char -> node = node.children.getOrPut(char) { Node() } }
                node.id = id
            }
        }
    }

    fun encode(text: String): LongArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        require(normalized.isNotEmpty()) { "Введите слово или короткую фразу" }
        require(normalized.codePointCount(0, normalized.length) <= 256) { "Максимум 256 символов за один перевод" }
        val result = ArrayList<Long>()
        normalized.split(Regex("\\s+")).forEach { word ->
            val input = "▁$word"
            val best = DoubleArray(input.length + 1) { Double.NEGATIVE_INFINITY }
            val previous = IntArray(input.length + 1) { -1 }
            val chosen = IntArray(input.length + 1) { unknownId }
            best[0] = 0.0
            for (start in input.indices) {
                if (!best[start].isFinite()) continue
                var node = root
                var end = start
                while (end < input.length) {
                    node = node.children[input[end]] ?: break
                    end++
                    if (node.id >= 0 && best[start] + scores[node.id] > best[end]) {
                        best[end] = best[start] + scores[node.id]
                        previous[end] = start
                        chosen[end] = node.id
                    }
                }
                val fallbackEnd = start + Character.charCount(input.codePointAt(start))
                if (best[start] + unknownScore > best[fallbackEnd]) {
                    best[fallbackEnd] = best[start] + unknownScore
                    previous[fallbackEnd] = start
                    chosen[fallbackEnd] = unknownId
                }
            }
            val reversed = ArrayList<Long>()
            var end = input.length
            while (end > 0) {
                check(previous[end] >= 0) { "Не удалось разобрать слово" }
                reversed += chosen[end].toLong()
                end = previous[end]
            }
            result += reversed.asReversed()
        }
        require(result.size < 128) { "Слишком длинная фраза для перевода" }
        result += eosId.toLong()
        return result.toLongArray()
    }

    fun decode(ids: List<Long>): String = ids.asSequence()
        .filter { it.toInt() != eosId && it.toInt() != padId }
        .joinToString("") { pieces.getOrElse(it.toInt()) { "�" } }
        .replace('▁', ' ').trim()
}
