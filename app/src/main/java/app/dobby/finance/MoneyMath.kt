package app.dobby.finance

/** The rules from the design doc, pure: bill anomaly, due windows, spend totals. */
object MoneyMath {
    const val ANOMALY_RATIO = 1.3
    const val ANOMALY_MIN_DIFF = 200.0

    data class BillRow(val biller: String, val amount: Double, val dueDate: String, val period: String?)
    data class Anomaly(val biller: String, val amount: Double, val median: Double, val history: List<Double>)

    /** Median of up to the last 6 earlier bills from the same biller; flagged when > 1.3× and > ₹200 above. */
    fun anomalies(bills: List<BillRow>): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        val byBiller = bills.groupBy { it.biller }
        for ((biller, rows) in byBiller) {
            val sorted = rows.sortedBy { it.dueDate }
            val latest = sorted.last()
            val hist = sorted.dropLast(1).takeLast(6).map { it.amount }
            if (hist.size < 2) continue
            val med = median(hist)
            if (latest.amount > ANOMALY_RATIO * med && latest.amount - med > ANOMALY_MIN_DIFF) out += Anomaly(biller, latest.amount, med, hist)
        }
        return out
    }

    fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** Latest bill per biller whose due date is within [today, today+days]. */
    fun due(bills: List<BillRow>, today: String, days: Int): List<BillRow> {
        val end = plusDays(today, days)
        return bills.groupBy { it.biller }.values.map { rows -> rows.maxByOrNull { it.dueDate }!! }
            .filter { it.dueDate >= today && it.dueDate <= end }.sortedBy { it.dueDate }
    }

    fun plusDays(iso: String, days: Int): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val c = java.util.Calendar.getInstance().apply { time = f.parse(iso)!!; add(java.util.Calendar.DAY_OF_MONTH, days) }
        return f.format(c.time)
    }
}
