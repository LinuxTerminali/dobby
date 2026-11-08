package app.dobby.model

/**
 * How much the current model can take. The harness reads these instead of hard-coding small-model
 * limits: a 4B on a phone needs short tool results and few steps; a 30B on a server can read whole
 * pages, chain more calls and cite several sources.
 */
data class Capacity(
    val name: String,
    val toolDataChars: Int,      // tool data fed back per call
    val maxSteps: Int,           // tool calls per turn
    val compactChars: Int,       // compaction budget for the conversation
    val researchPages: Int,      // pages read by web.research
    val passageWindow: Int,      // chars per extracted passage
    val passagesPerPage: Int,
    val searchResults: Int,
) {
    companion object {
        val SMALL = Capacity("small", toolDataChars = 1_500, maxSteps = 5, compactChars = 10_000, researchPages = 3, passageWindow = 450, passagesPerPage = 2, searchResults = 5)
        val LARGE = Capacity("large", toolDataChars = 8_000, maxSteps = 10, compactChars = 60_000, researchPages = 5, passageWindow = 900, passagesPerPage = 3, searchResults = 8)

        /** Remote endpoints are assumed to be big models; on-device files are small. */
        fun forEntry(e: ModelEntry): Capacity = if (e.isRemote) LARGE else SMALL

        @Volatile var current: Capacity = SMALL
    }
}
