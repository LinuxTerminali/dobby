package app.dobby.model

import app.dobby.mcp.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalsTest {
    @Test fun readsAndWritesRunWithoutAsking() {
        Approvals.autoApprove = false; Approvals.headless = false
        assertEquals(Approvals.Decision.ALLOW_ONCE, Approvals.preDecide(Effect.READ, false))
        assertEquals(Approvals.Decision.ALLOW_ONCE, Approvals.preDecide(Effect.WRITE, false))
    }

    @Test fun destructiveAsksUnlessAllowedOrEval() {
        Approvals.autoApprove = false; Approvals.headless = false
        assertNull(Approvals.preDecide(Effect.DESTRUCTIVE, false))
        assertEquals(Approvals.Decision.ALLOW_ONCE, Approvals.preDecide(Effect.DESTRUCTIVE, true))
        Approvals.autoApprove = true
        assertEquals(Approvals.Decision.ALLOW_ONCE, Approvals.preDecide(Effect.DESTRUCTIVE, false))
        Approvals.autoApprove = false
    }

    @Test fun headlessDeniesDestructive() {
        Approvals.headless = true
        assertEquals(Approvals.Decision.DENY, Approvals.preDecide(Effect.DESTRUCTIVE, false))
        Approvals.headless = false
    }
}
