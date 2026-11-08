package app.dobby.finance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import app.dobby.util.Fmt

/**
 * Parsed money records from SMS. Rescan reads the inbox (last N days), parses with SmsTemplates,
 * and upserts; rows are keyed by the SMS hash so a rescan is idempotent. On the phone only.
 */
class Ledger(private val context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE txn(hash TEXT PRIMARY KEY, ts INTEGER, amount REAL, direction TEXT, last4 TEXT, merchant TEXT, balance REAL, channel TEXT)")
        db.execSQL("CREATE TABLE bal(hash TEXT PRIMARY KEY, ts INTEGER, last4 TEXT, balance REAL)")
        db.execSQL("CREATE TABLE bill(hash TEXT PRIMARY KEY, ts INTEGER, biller TEXT, amount REAL, due_date TEXT, period TEXT)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    data class ScanResult(val scanned: Int, val txns: Int, val bills: Int, val balances: Int)

    fun rescan(days: Int = 180): ScanResult {
        val since = System.currentTimeMillis() - days * 86_400_000L
        var scanned = 0; var t = 0; var b = 0; var bal = 0
        val db = writableDatabase
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                scanned++
                val from = c.getString(0) ?: ""; val body = c.getString(1) ?: ""; val ts = c.getLong(2)
                val hash = Integer.toHexString((from + body).hashCode()) + ts
                when (val r = SmsTemplates.parse(from, body)) {
                    is SmsTemplates.Record.Txn -> {
                        db.insertWithOnConflict("txn", null, ContentValues().apply { put("hash", hash); put("ts", ts); put("amount", r.amount); put("direction", r.direction); put("last4", r.last4); put("merchant", r.merchant); put("balance", r.balance); put("channel", r.channel) }, SQLiteDatabase.CONFLICT_IGNORE).also { if (it > 0) t++ }
                        if (r.balance != null) db.insertWithOnConflict("bal", null, ContentValues().apply { put("hash", hash); put("ts", ts); put("last4", r.last4); put("balance", r.balance) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    is SmsTemplates.Record.Balance -> db.insertWithOnConflict("bal", null, ContentValues().apply { put("hash", hash); put("ts", ts); put("last4", r.last4); put("balance", r.balance) }, SQLiteDatabase.CONFLICT_IGNORE).also { if (it > 0) bal++ }
                    is SmsTemplates.Record.Bill -> db.insertWithOnConflict("bill", null, ContentValues().apply { put("hash", hash); put("ts", ts); put("biller", r.biller); put("amount", r.amount); put("due_date", r.dueDate); put("period", r.period) }, SQLiteDatabase.CONFLICT_IGNORE).also { if (it > 0) b++ }
                    else -> {}
                }
            }
        }
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('last_scan', ?)", arrayOf(System.currentTimeMillis().toString()))
        return ScanResult(scanned, t, b, bal)
    }

    fun lastScan(): Long = readableDatabase.rawQuery("SELECT v FROM meta WHERE k='last_scan'", null).use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }

    fun bills(): List<MoneyMath.BillRow> = readableDatabase.rawQuery("SELECT biller, amount, due_date, period FROM bill ORDER BY due_date", null).use { c ->
        generateSequence { if (c.moveToNext()) MoneyMath.BillRow(c.getString(0), c.getDouble(1), c.getString(2), c.getString(3)) else null }.toList()
    }

    /** Latest known balance per account. */
    fun balances(): List<Triple<String, Double, Long>> = readableDatabase.rawQuery(
        "SELECT last4, balance, ts FROM bal b WHERE ts = (SELECT MAX(ts) FROM bal WHERE last4 = b.last4) GROUP BY last4", null).use { c ->
        generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getDouble(1), c.getLong(2)) else null }.toList()
    }

    data class Spend(val merchant: String, val total: Double, val count: Int)
    fun spend(days: Int, merchantLike: String?): List<Spend> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val where = StringBuilder("direction='debit' AND ts > ?"); val args = mutableListOf(since.toString())
        if (!merchantLike.isNullOrBlank()) { where.append(" AND merchant LIKE ?"); args += "%$merchantLike%" }
        return readableDatabase.rawQuery("SELECT COALESCE(merchant,'(unknown)'), SUM(amount), COUNT(*) FROM txn WHERE $where GROUP BY merchant ORDER BY SUM(amount) DESC LIMIT 15", args.toTypedArray()).use { c ->
            generateSequence { if (c.moveToNext()) Spend(c.getString(0), c.getDouble(1), c.getInt(2)) else null }.toList()
        }
    }

    fun describeBalances() = balances().joinToString(" ") { (l4, b, ts) -> "Account x$l4: ${Fmt.inr(b)} as of ${Fmt.dateTime(ts)}." }
}
