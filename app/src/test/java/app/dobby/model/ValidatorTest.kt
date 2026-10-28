package app.dobby.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    @Test fun droppedDigits() {
        assertTrue(Validator.isDroppedDigits("35", "365"))
        assertTrue(Validator.isDroppedDigits("9181100000", "919811100000"))
        assertFalse(Validator.isDroppedDigits("365", "365"))
        assertFalse(Validator.isDroppedDigits("36", "35"))
        assertFalse(Validator.isDroppedDigits("", "365"))
    }

    @Test fun argsFlagMangledUserNumber() {
        val issues = Validator.checkArgs("who called me 365 days ago?", JSONObject("""{"days":35}"""))
        assertEquals(1, issues.size); assertEquals("365", issues[0].expected); assertEquals("days", issues[0].key)
        assertTrue(Validator.checkArgs("who called me 365 days ago?", JSONObject("""{"days":365}""")).isEmpty())
        assertTrue(Validator.checkArgs("who called me 365 days ago?", JSONObject("""{"who":"Mom","days":7}""")).isEmpty())
        assertTrue(Validator.checkArgs("call mom", JSONObject("""{"days":30}""")).isEmpty())
    }

    @Test fun replyGroundedAgainstSources() {
        assertNull(Validator.checkReply("You called Mom on 26 Oct, 10 min.", listOf("""{"summary":"Outgoing call to Mom on Sun 26 Oct, 8:00 PM, 10 min 0 s."}""")))
        val issue = Validator.checkReply("Total due is Rs 98,707 by 11/11/2026", listOf("Total Amt Due Rs 98708; Payable by 11/11/2026"))!!
        assertEquals(setOf("98707"), issue.ungrounded)
        assertNull(Validator.checkReply("Today is 28 Oct 2026", emptyList(), setOf("28", "10", "2026")))
        assertNull(Validator.checkReply("You have 3 reminders and 2 places.", emptyList()))
    }

    @Test fun argsFlagMangledUserWord() {
        val issues = Validator.checkArgs("when I reach moengage remind me to drink water", JSONObject("""{"place":"engage","event":"enter"}"""))
        assertEquals(1, issues.size); assertEquals("moengage", issues[0].expected)
        assertTrue(Validator.checkArgs("when I reach moengage remind me", JSONObject("""{"place":"moengage"}""")).isEmpty())
        assertTrue(Validator.checkArgs("remind me to drink water", JSONObject("""{"title":"drink water"}""")).isEmpty())
    }

    @Test fun replyFlagsCorruptedTimes() {
        val src = listOf("Tumkur arr 23:56 dep 23:58; Davangere 3:20 to 3:22")
        assertNull(Validator.checkReply("Tumkur 23:56 to 23:58", src))
        val issue = Validator.checkReply("Tumkur 23:000 to 23:58, Davangere 3:2:0000", src)!!
        assertTrue(issue.ungrounded.toString(), issue.ungrounded.contains("23:000"))
    }

    @Test fun correctionsMentionTheNumbers() {
        val c = Validator.argCorrection(listOf(Validator.ArgIssue("days", "35", "365")))
        assertTrue(c.contains("365") && c.contains("days=35"))
        val r = Validator.marker(Validator.ReplyIssue(setOf("98707")))
        assertEquals("⚠ Could not verify: 98707", r)
    }
}
