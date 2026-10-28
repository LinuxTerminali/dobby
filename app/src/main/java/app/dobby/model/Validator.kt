package app.dobby.model

import org.json.JSONObject

/**
 * Code-side checks on what the model produces. Pure Kotlin, unit-tested.
 *  - checkArgs: a numeric argument that looks like a mangled version of a number the user typed
 *    (e.g. 35 for 365) blocks the call.
 *  - checkReply: numbers in the reply must come from the user's text, the tool results or today's date.
 */
object Validator {
    data class ArgIssue(val key: String, val got: String, val expected: String)
    data class ReplyIssue(val ungrounded: Set<String>)

    private val NUM = Regex("\\d[\\d,]*(?:\\.\\d+)?")
    private val TIMEISH = Regex("\\b\\d{1,2}(?::\\d{1,4})+\\b")
    private val TIME_OK = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
    private val WORD = Regex("[A-Za-z][A-Za-z0-9]{4,}")

    fun numbers(text: String): Set<String> = NUM.findAll(text).map { norm(it.value) }.toSet()

    /** Clock times like 23:56, checked whole. Malformed ones ("23:000", "3:2:0000") are returned as-is and always fail. */
    fun times(text: String): Set<String> = TIMEISH.findAll(text).map { it.value }.map { if (TIME_OK.matches(it)) it.trimStart('0').ifEmpty { "0" } else it }.toSet()

    /** Longer words the user typed (names, places): candidates for dropped-character checks. */
    fun userWords(text: String): Set<String> = WORD.findAll(text).map { it.value.lowercase() }.toSet()

    fun norm(n: String): String {
        val s = n.replace(",", "")
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s.trimStart('0').ifEmpty { "0" }
    }

    /** True when `short` is `long` with one or more digits removed (and not equal). */
    fun isDroppedDigits(short: String, long: String): Boolean {
        if (short == long || short.isEmpty() || short.length >= long.length) return false
        var i = 0
        for (c in long) if (i < short.length && short[i] == c) i++
        return i == short.length
    }

    /** Args that look like corrupted copies of what the user typed: numbers (365→35) or words (moengage→engage). */
    fun checkArgs(userText: String, args: JSONObject): List<ArgIssue> {
        val userNums = numbers(userText).filter { it.length >= 2 && !it.contains('.') }
        val words = userWords(userText)
        val issues = mutableListOf<ArgIssue>()
        for (k in args.keys()) {
            when (val v = args.get(k)) {
                is Number -> { val s = norm(v.toString()); if (s !in userNums) userNums.firstOrNull { isDroppedDigits(s, it) }?.let { issues += ArgIssue(k, s, it) } }
                is String -> {
                    if (Regex("^\\d{1,9}$").matches(v)) { if (v !in userNums) userNums.firstOrNull { isDroppedDigits(v, it) }?.let { issues += ArgIssue(k, v, it) } }
                    else for (w in WORD.findAll(v).map { it.value.lowercase() }) {
                        if (w in words) continue
                        words.firstOrNull { u -> u.length >= 6 && isDroppedDigits(w, u) }?.let { issues += ArgIssue(k, w, it) }
                    }
                }
                else -> {}
            }
        }
        return issues.distinct()
    }

    /**
     * Numbers in the reply not present in any source. Ignores 0–12 and the current day/month/year
     * so "today is 28 Oct 2026" without a tool call is fine.
     */
    fun checkReply(reply: String, sources: List<String>, todayNums: Set<String> = emptySet()): ReplyIssue? {
        val allowed = sources.flatMap { numbers(it) }.toSet() + todayNums.map { norm(it) }
        val badNums = numbers(reply).filter { it !in allowed && (it.toDoubleOrNull() ?: 99.0) > 12 }
        val allowedTimes = sources.flatMap { times(it) }.filter { TIME_OK.matches(it) || TIME_OK.matches("0$it") }.toSet()
        val badTimes = times(reply).filter { it !in allowedTimes }
        val bad = (badNums + badTimes).toSet()
        return if (bad.isEmpty()) null else ReplyIssue(bad)
    }

    fun argCorrection(i: List<ArgIssue>) =
        "Do not run that. The user said ${i.joinToString(", ") { it.expected }} but you passed ${i.joinToString(", ") { "${it.key}=${it.got}" }}. Call the tool again with the user's exact value, or ask the user."

    fun replyCorrection(i: ReplyIssue) =
        "Your answer contains numbers that are not in the tool results or the user's message: ${i.ungrounded.joinToString(", ")}. Answer again using only numbers, names and dates exactly as they appear in the results. If the results do not contain the answer, say so."

    fun marker(i: ReplyIssue) = "⚠ Could not verify: " + i.ungrounded.joinToString(", ")
}
