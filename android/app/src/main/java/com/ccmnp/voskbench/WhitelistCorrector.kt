package com.ccmnp.voskbench

/**
 * Пословный whitelist-корректор для free-form VOSK вывода.
 *
 * После free-form транскрипции каждое слово корректируется к ближайшему
 * слову из whitelist-словаря (edit distance ≤ 3). Слова, не нашедшие
 * соответствия — выбрасываются.
 *
 * Адаптивный guard: коррекция применяется только если raw уже содержит
 * ≥ 2 whitelist-слов (вероятно, это валидная команда с искажениями).
 *
 * Порт Python-реализации: whitelist_corrector.py
 * Статус DHF: ФПР-0011, ОТМЕНЕНА (не решает accuracy vs FP tradeoff)
 */
object WhitelistCorrector {

    private const val MAX_DIST = 3
    private const val MIN_WHITELIST_WORDS = 2

    /** Извлечь все уникальные слова из фраз грамматики. */
    fun buildWhitelist(grammar: List<String>): Set<String> {
        val words = mutableSetOf<String>()
        for (phrase in grammar) {
            for (w in phrase.lowercase().split("\\s+".toRegex())) {
                if (w.isNotBlank()) words.add(w)
            }
        }
        return words
    }

    /** Расстояние Левенштейна. */
    fun editDistance(a: String, b: String): Int {
        val alen = a.length
        val blen = b.length
        var prev = IntArray(blen + 1) { it }
        for (i in 1..alen) {
            val curr = IntArray(blen + 1)
            curr[0] = i
            for (j in 1..blen) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j], curr[j - 1], prev[j - 1])
                }
            }
            prev = curr
        }
        return prev[blen]
    }

    /** Найти ближайшее whitelist-слово. null если расстояние > MAX_DIST. */
    fun correctWord(word: String, whitelist: Set<String>): String? {
        var best: String? = null
        var bestDist = MAX_DIST + 1
        for (wl in whitelist) {
            val d = editDistance(word, wl)
            if (d < bestDist) {
                bestDist = d
                best = wl
                if (d == 0) break
            }
        }
        return if (bestDist <= MAX_DIST) best else null
    }

    /**
     * Пропустить каждое слово через correctWord.
     * Выбросить несопоставленные слова.
     */
    fun correctPhrase(raw: String, whitelist: Set<String>): String {
        val words = raw.lowercase().split("\\s+".toRegex())
        val corrected = mutableListOf<String>()
        for (w in words) {
            val cw = correctWord(w, whitelist)
            if (cw != null) corrected.add(cw)
        }
        return corrected.joinToString(" ")
    }

    /**
     * Проверить: стоит ли применять корректор?
     * Если raw уже содержит ≥ MIN_WHITELIST_WORDS whitelist-слов —
     * вероятно, это валидная команда. Корректируем.
     * Если нет — скорее всего шум/OOV. Не трогаем.
     */
    fun shouldCorrect(raw: String, whitelist: Set<String>): Boolean {
        val words = raw.lowercase().split("\\s+".toRegex())
        return words.count { it in whitelist } >= MIN_WHITELIST_WORDS
    }
}
