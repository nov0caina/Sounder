package org.nov0caina.sounder.search

import java.text.Normalizer

/**
 * Typo-tolerant and accent-insensitive string matcher combining diacritic normalization,
 * phonetic skeleton equivalence, and token-level Damerau-Levenshtein edit distance.
 */
object FuzzySearchMatcher {

    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val DUPLICATE_CHARS_REGEX = Regex("([a-z0-9])\\1+")

    /**
     * Normalizes text by stripping diacritics, converting to lowercase, and collapsing punctuation
     * and repeated whitespace into single spaces.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        val alnum = NON_ALNUM_REGEX.replace(noAccents.lowercase(), " ")
        return WHITESPACE_REGEX.replace(alnum, " ").trim()
    }

    /**
     * Produces a phonetic skeleton so common bilingual/orthographic misspellings (e.g.
     * "eleveishon" vs "elevation", "bad buny" vs "bad bunny", "kendrik" vs "kendrick") match.
     */
    fun phoneticSkeleton(text: String): String {
        val norm = normalize(text).replace(" ", "")
        if (norm.isEmpty()) return ""
        val collapsed = DUPLICATE_CHARS_REGEX.replace(norm, "$1")
        val replaced = collapsed
            .replace("tion", "son")
            .replace("sion", "son")
            .replace("shon", "son")
            .replace("sh", "s")
            .replace("ch", "s")
            .replace("ph", "f")
            .replace("qu", "c")
            .replace("ck", "c")
            .replace("v", "b")
            .replace("z", "s")
            .replace("k", "c")
            .replace("y", "i")
            .replace("w", "u")
        val withoutSilentH = if (replaced.length > 1) {
            replaced.first() + replaced.substring(1).replace("h", "")
        } else {
            replaced
        }
        return DUPLICATE_CHARS_REGEX.replace(withoutSilentH, "$1")
    }

    /**
     * Computes Damerau-Levenshtein distance allowing insertions, deletions, substitutions,
     * and adjacent character transpositions.
     */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val lenA = a.length
        val lenB = b.length
        val dp = Array(lenA + 1) { IntArray(lenB + 1) }

        for (i in 0..lenA) dp[i][0] = i
        for (j in 0..lenB) dp[0][j] = j

        for (i in 1..lenA) {
            for (j in 1..lenB) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, dp[i - 2][j - 2] + cost)
                }
                dp[i][j] = best
            }
        }
        return dp[lenA][lenB]
    }

    /**
     * Returns a relevance score in `0..100` indicating how strongly [candidate] matches [query].
     * A score of `0` indicates no match; higher scores indicate closer matches.
     */
    fun scoreMatch(candidate: String, query: String): Int {
        val normQ = normalize(query)
        val normC = normalize(candidate)
        if (normQ.isEmpty() || normC.isEmpty()) return 0

        if (normC == normQ) return 100
        if (normC.startsWith(normQ)) return 90
        if (normC.contains(normQ)) return 80

        val compactQ = normQ.replace(" ", "")
        val compactC = normC.replace(" ", "")
        if (compactC.startsWith(compactQ)) return 75
        if (compactC.contains(compactQ)) return 68

        val qTokens = normQ.split(" ").filter { it.isNotEmpty() }
        val cTokens = normC.split(" ").filter { it.isNotEmpty() }
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0

        var worstTokenScore = 100
        for (qt in qTokens) {
            val bestForToken = bestTokenScore(qt, cTokens)
            if (bestForToken == 0) {
                worstTokenScore = 0
                break
            }
            worstTokenScore = minOf(worstTokenScore, bestForToken)
        }
        if (worstTokenScore > 0) return worstTokenScore

        // Single/compact whole-string fallback for missing spaces (e.g. "cold play" -> "Coldplay")
        val maxCompactDist = when {
            compactQ.length <= 2 -> 0
            compactQ.length <= 5 -> 1
            else -> 2
        }
        val compactDist = editDistance(compactQ, compactC)
        if (compactDist <= maxCompactDist) {
            return (55 - compactDist * 10).coerceAtLeast(30)
        }
        val skelQ = phoneticSkeleton(normQ)
        val skelC = phoneticSkeleton(normC)
        if (skelQ.length >= 3 && (skelC.contains(skelQ) || editDistance(skelQ, skelC) <= 1)) {
            return 45
        }

        return 0
    }

    private fun bestTokenScore(queryToken: String, candidateTokens: List<String>): Int {
        val skelQ = phoneticSkeleton(queryToken)
        val maxDist = when {
            queryToken.length <= 2 -> 0
            queryToken.length <= 4 -> 1
            else -> 2
        }

        var best = 0
        for (ct in candidateTokens) {
            when {
                ct == queryToken -> return 75
                ct.startsWith(queryToken) -> best = maxOf(best, 70)
                ct.contains(queryToken) -> best = maxOf(best, 65)
                else -> {
                    val skelC = phoneticSkeleton(ct)
                    if (skelQ.length >= 3 && skelC.isNotEmpty()) {
                        if (skelC == skelQ) {
                            best = maxOf(best, 55)
                        } else if (skelC.startsWith(skelQ)) {
                            best = maxOf(best, 50)
                        } else {
                            val skelMax = if (skelQ.length <= 4) 1 else 2
                            val skelDist = editDistance(skelQ, skelC)
                            if (skelDist <= skelMax && queryToken.length >= 4) {
                                best = maxOf(best, 44 - skelDist * 5)
                            }
                        }
                    }

                    if (maxDist > 0) {
                        val directDist = editDistance(queryToken, ct)
                        if (directDist <= maxDist) {
                            best = maxOf(best, 42 - directDist * 6)
                        } else if (ct.length > queryToken.length + 1 && queryToken.length >= 4) {
                            val prefixSlice = ct.take(minOf(ct.length, queryToken.length + 1))
                            val prefixDist = editDistance(queryToken, prefixSlice)
                            if (prefixDist <= maxDist) {
                                best = maxOf(best, 38 - prefixDist * 6)
                            }
                        }
                    }
                }
            }
        }
        return best
    }
}
