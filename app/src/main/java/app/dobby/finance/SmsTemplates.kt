package app.dobby.finance

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bank / biller SMS → structured record. Regex first (fast, exact); the model is never asked to
 * parse money text. Formats are examples of common Indian bank wording; extend per bank.
 * Pure Kotlin, unit-tested.
 */
object SmsTemplates {
    sealed class Record {
        data class Txn(val amount: Double, val direction: String, val last4: String, val merchant: String?, val balance: Double?, val channel: String) : Record()
        data class Balance(val last4: String, val balance: Double) : Record()
        data class Bill(val biller: String, val amount: Double, val dueDate: String /* yyyy-MM-dd */, val period: String?) : Record()
        object Otp : Record()
    }

    private const val AMT = """(?:Rs\.?|INR|₹)\s?(?<amount>[\d,]+(?:\.\d{1,2})?)"""
    private const val ACCT = """(?:A/c|Acct|a/c|Account|Card)\s?(?:no\.?)?\s?(?:ending\s)?(?:\*+|X+|x+)?(?<last4>\d{4})"""
    private const val BAL = """(?:Avl|Available|Avail)\.?\s?(?:Bal|Balance)\.?:?\s?(?:is\s)?(?:Rs\.?|INR|₹)\s?(?<balance>[\d,]+(?:\.\d{1,2})?)"""
    private const val MERCH = """(?<merchant>[A-Za-z0-9 &._'*-]+?)"""

    private val OTP = Regex("""\bOTP\b|one[- ]time[- ]pass""", RegexOption.IGNORE_CASE)
    private val DEBIT = Regex("""$AMT\s+(?:debited|withdrawn|spent)\s+(?:from\s+)?(?:.*?)?$ACCT(?:.*?\b(?:to|at|for)\s+$MERCH(?=[.,;]|\s+Avl|\s+Ref|\s+on\s|$))?(?:.*?$BAL)?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CARD = Regex("""$AMT\s+spent\s+on\s+.*?(?:Card|card)\s*(?:ending\s)?(?:x|X|\*)*(?<last4>\d{4})\s+at\s+$MERCH(?=\s+on\s|[.,;]|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CREDIT = Regex("""$AMT\s+credited\s+to\s+$ACCT(?:.*?\b(?:from|by)\s+$MERCH(?=[.,;]|\s+Avl|\s+Ref|$))?(?:.*?$BAL)?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BALANCE = Regex("""$ACCT\s+(?:has|balance is|bal is)\s+(?:a\s+)?(?:balance\s+(?:of\s+)?)?(?:Rs\.?|INR|₹)\s?(?<balance>[\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val BILL = Regex("""(?:Your\s+)?(?<biller>[A-Za-z][A-Za-z ]{1,30}?)\s+bill\s+(?:of\s+|amount\s+)?$AMT(?:\s+for\s+(?<period>[A-Za-z]{3,9}(?:\s?\d{2,4})?))?.{0,40}?\b(?:due|payable)\s+(?:on|by)\s+(?<due>\d{1,2}[-/ ][A-Za-z0-9]{2,3}[-/ ]\d{2,4})""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STATEMENT = Regex("""Total\s+Amt\s+Due\s+(?:Rs\.?|INR|₹)\s?(?<amount>[\d,]+(?:\.\d{1,2})?).{0,80}?Payable\s+by\s+(?<due>\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\d{2,4})""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CARD_IN_STMT = Regex("""(?<biller>[A-Z][A-Za-z]+)\s+Credit\s+Card""")

    fun parse(sender: String, body: String): Record? {
        if (OTP.containsMatchIn(body)) return Record.Otp
        CARD.find(body)?.let { m -> return Record.Txn(num(m, "amount"), "debit", m.groups["last4"]!!.value, m.groups["merchant"]?.value?.trim(), null, "card") }
        DEBIT.find(body)?.let { m -> return Record.Txn(num(m, "amount"), "debit", m.groups["last4"]!!.value, m.groups["merchant"]?.value?.trim(), m.groups["balance"]?.value?.let(::toDouble), "bank") }
        CREDIT.find(body)?.let { m -> return Record.Txn(num(m, "amount"), "credit", m.groups["last4"]!!.value, m.groups["merchant"]?.value?.trim(), m.groups["balance"]?.value?.let(::toDouble), "bank") }
        BALANCE.find(body)?.let { m -> return Record.Balance(m.groups["last4"]!!.value, num(m, "balance")) }
        STATEMENT.find(body)?.let { m ->
            val biller = CARD_IN_STMT.find(body)?.groupValues?.get(1)?.uppercase()?.let { "$it CARD" } ?: senderName(sender)
            return Record.Bill(biller, num(m, "amount"), isoDate(m.groups["due"]!!.value) ?: return null, null)
        }
        BILL.find(body)?.let { m ->
            val biller = m.groups["biller"]!!.value.trim().uppercase().let { if (it.length > 20) senderName(sender) else it }
            return Record.Bill(biller, num(m, "amount"), isoDate(m.groups["due"]!!.value) ?: return null, m.groups["period"]?.value?.trim())
        }
        return null
    }

    fun toDouble(s: String) = s.replace(",", "").toDouble()
    private fun num(m: MatchResult, g: String) = toDouble(m.groups[g]!!.value)

    /** Sender ids like VM-BESCOM, AD-AIRTEL, JD-MYSBIC-S → BESCOM, AIRTEL, MYSBIC. */
    fun senderName(sender: String): String = sender.uppercase().split("-").filter { it.length > 2 }.maxByOrNull { it.length } ?: sender.uppercase()

    private val FORMATS = listOf("dd-MM-yy", "dd-MM-yyyy", "dd/MM/yy", "dd/MM/yyyy", "dd-MMM-yy", "dd-MMM-yyyy", "dd MMM yyyy", "dd MMM yy")
    fun isoDate(s: String): String? {
        val t = s.trim().replace(Regex("\\s+"), "-")
        for (f in FORMATS) {
            val p = SimpleDateFormat(f.replace(" ", "-"), Locale.US).apply { isLenient = false }
            runCatching { p.parse(t) }.getOrNull()?.let { return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) }
        }
        return null
    }
}
