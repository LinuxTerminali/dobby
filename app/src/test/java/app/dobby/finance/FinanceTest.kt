package app.dobby.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceTest {
    private fun txn(s: String, sender: String = "VM-HDFCBK") = SmsTemplates.parse(sender, s) as SmsTemplates.Record.Txn

    @Test fun parsesDebitWithBalance() {
        val t = txn("Rs.640.00 debited from A/c XX1234 on 05-10-26 to SWIGGY. Avl Bal Rs.1,16,810.10. Not you? Call 18002586161")
        assertEquals(640.0, t.amount, 0.0); assertEquals("debit", t.direction); assertEquals("1234", t.last4); assertEquals("SWIGGY", t.merchant); assertEquals(116810.10, t.balance!!, 0.0)
    }

    @Test fun parsesCreditAndCardSpend() {
        val c = txn("INR 95,000.00 credited to A/c XX1234 on 01-10-26 by MOENGAGE SALARY. Avl Bal INR 1,32,450.10")
        assertEquals("credit", c.direction); assertEquals("MOENGAGE SALARY", c.merchant); assertEquals(132450.10, c.balance!!, 0.0)
        val k = txn("Rs.237.00 spent on your SBI Credit Card ending 0020 at SWIGGYPVTLTDFOOD1 on 25/10/26. Trxn. not done by you? Report at https://sbicard.com/Dispute", "VM-SBICRD-S")
        assertEquals("0020", k.last4); assertEquals("SWIGGYPVTLTDFOOD1", k.merchant); assertEquals("card", k.channel)
    }

    @Test fun parsesBalanceOnly() {
        val b = SmsTemplates.parse("BZ-SBIINB", "Your A/c X5678 has balance Rs.80,000.00 as on 22-10-26. -SBI") as SmsTemplates.Record.Balance
        assertEquals("5678", b.last4); assertEquals(80000.0, b.balance, 0.0)
    }

    @Test fun parsesUtilityBillAndCardStatement() {
        val b = SmsTemplates.parse("VM-BESCOM", "Your BESCOM bill of Rs.2,140.00 for Oct 2026 is due on 28-10-2026. Pay via BBPS.") as SmsTemplates.Record.Bill
        assertEquals("BESCOM", b.biller); assertEquals(2140.0, b.amount, 0.0); assertEquals("2026-10-28", b.dueDate); assertEquals("Oct 2026", b.period)
        val s = SmsTemplates.parse("JD-MYSBIC-S", "E-statement of SBI Credit Card ending XX20 dated 22/10/2026 has been mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 98708; Min Amt Due Rs 1974; Payable by 11/11/2026. Click https://sbicard.com/quickpaynet") as SmsTemplates.Record.Bill
        assertEquals("SBI CARD", s.biller); assertEquals(98708.0, s.amount, 0.0); assertEquals("2026-11-11", s.dueDate)
    }

    @Test fun otpAndPromoAreNotRecords() {
        assertTrue(SmsTemplates.parse("VM-HDFCBK", "123456 is the OTP for your HDFC Bank NetBanking login.") is SmsTemplates.Record.Otp)
        assertNull(SmsTemplates.parse("TX-OFFERS", "Flat 50% off on your next order! Use code SAVE50."))
    }

    @Test fun anomalyAndDue() {
        val bills = listOf(
            MoneyMath.BillRow("BESCOM", 980.0, "2026-07-28", null), MoneyMath.BillRow("BESCOM", 1010.0, "2026-08-28", null),
            MoneyMath.BillRow("BESCOM", 1120.0, "2026-09-28", null), MoneyMath.BillRow("BESCOM", 2140.0, "2026-10-28", null),
            MoneyMath.BillRow("AIRTEL", 799.0, "2026-09-30", null), MoneyMath.BillRow("AIRTEL", 799.0, "2026-10-30", null))
        val a = MoneyMath.anomalies(bills)
        assertEquals(1, a.size); assertEquals("BESCOM", a[0].biller); assertEquals(1010.0, a[0].median, 0.0)
        val due = MoneyMath.due(bills, "2026-10-24", 7)
        assertEquals(listOf("BESCOM", "AIRTEL"), due.map { it.biller })
        assertTrue(MoneyMath.due(bills, "2026-11-05", 7).isEmpty())
    }
}
