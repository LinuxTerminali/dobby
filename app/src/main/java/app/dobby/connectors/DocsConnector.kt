package app.dobby.connectors

import android.content.Context
import app.dobby.mcp.ToolSpec
import org.json.JSONObject

class DocsConnector(private val context: Context, private val auth: GoogleAuth) : Connector {
    override val id = "docs"
    override val title = "Google Docs"
    override val description = "Read the text of a Google Doc by id (read-only)."
    override val requirement = Requirement.GoogleOAuth(listOf(GoogleAuth.SCOPE_DOCS))
    override fun isReady(context: Context) = auth.isSignedIn() && GoogleAuth.SCOPE_DOCS in auth.grantedScopes()

    override fun tools() = listOf(
        ToolSpec("read", "Read a Google Doc's text. The id is the long string in its URL.",
            ToolSpec.schema(listOf(Triple("document_id", "string", "Document id from the URL")), listOf("document_id"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject {
        require(tool == "read") { "unknown tool $tool" }
        val doc = JSONObject(auth.get("https://docs.googleapis.com/v1/documents/${args.getString("document_id")}"))
        val sb = StringBuilder()
        val content = doc.optJSONObject("body")?.optJSONArray("content")
        if (content != null) for (i in 0 until content.length()) {
            val els = content.getJSONObject(i).optJSONObject("paragraph")?.optJSONArray("elements") ?: continue
            for (j in 0 until els.length()) sb.append(els.getJSONObject(j).optJSONObject("textRun")?.optString("content") ?: "")
        }
        return JSONObject().put("title", doc.optString("title")).put("text", sb.toString().take(12000))
    }
}
