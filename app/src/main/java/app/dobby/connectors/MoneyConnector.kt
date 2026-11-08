package app.dobby.connectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.dobby.finance.Ledger
import app.dobby.finance.MoneyMath
import app.dobby.mcp.ToolSpec
import app.dobby.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bills, balances and spend from bank/biller SMS, parsed by code. The first scenarios from the design. */
class MoneyConnector(private val context: Context) : Connector {
    override val id = "money"
    override val title = "Money"
    override val description = "Bills due, unusual bills, account balances and spend, parsed from bank SMS."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.READ_SMS))
    override fun isReady(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    private val ledger by lazy { Ledger(context) }

    override fun tools() = listOf(
        ToolSpec("bills_due", "Bills due soon, from parsed bank and biller SMS (electricity, broadband, credit card statements).",
            ToolSpec.schema(listOf(Triple("days", "integer", "Window from today. Default 7")))),
        ToolSpec("bill_anomalies", "Bills that are much higher than that biller's usual amount (>1.3x the median of the last 6).", ToolSpec.schema(emptyList())),
        ToolSpec("balances", "Latest known balance per bank account, from SMS alerts.", ToolSpec.schema(emptyList())),
        ToolSpec("spend", "Total spent by merchant over a period.",
            ToolSpec.schema(listOf(Triple("days", "integer", "Period. Default 30"), Triple("merchant", "string", "Filter by merchant name, optional")))),
        ToolSpec("rescan", "Re-read bank SMS into the ledger (last 180 days). Run if results look stale.", ToolSpec.schema(emptyList())),
    )

    private fun ensureScanned() { if (System.currentTimeMillis() - ledger.lastScan() > 6 * 3_600_000L) ledger.rescan() }

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "rescan" -> ledger.rescan().let { JSONObject().put("summary", "Scanned ${it.scanned} SMS: ${it.txns} new transactions, ${it.bills} new bills, ${it.balances} balance alerts.").put("scanned", it.scanned) }
        "bills_due" -> {
            ensureScanned()
            val days = args.optInt("days", 7); val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val due = MoneyMath.due(ledger.bills(), today, days)
            JSONObject().put("summary", if (due.isEmpty()) "No bills due in the next $days days." else due.joinToString(" ") { "${it.biller}: ${Fmt.inr(it.amount)} due ${Fmt.isoDate(it.dueDate)}." })
                .put("bills", JSONArray(due.map { JSONObject().put("biller", it.biller).put("amount", it.amount).put("due", it.dueDate) })).put("count", due.size)
        }
        "bill_anomalies" -> {
            ensureScanned()
            val a = MoneyMath.anomalies(ledger.bills())
            JSONObject().put("summary", if (a.isEmpty()) "No unusual bills." else a.joinToString(" ") { "${it.biller}: ${Fmt.inr(it.amount)} vs usual ${Fmt.inr(it.median)} (last ${it.history.size}: ${it.history.joinToString(", ") { h -> Fmt.inr(h) }})." })
                .put("count", a.size)
        }
        "balances" -> { ensureScanned(); val s = ledger.describeBalances(); JSONObject().put("summary", s.ifBlank { "No balance alerts found in SMS." }).put("count", ledger.balances().size) }
        "spend" -> {
            ensureScanned()
            val days = args.optInt("days", 30); val rows = ledger.spend(days, args.optString("merchant", null))
            val total = rows.sumOf { it.total }
            JSONObject().put("summary", if (rows.isEmpty()) "No debits found in the last $days days." else "Spent ${Fmt.inr(total)} in the last $days days. " + rows.take(8).joinToString(" ") { "${it.merchant}: ${Fmt.inr(it.total)} (${it.count}×)." })
                .put("total", total).put("count", rows.size)
        }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }
}
