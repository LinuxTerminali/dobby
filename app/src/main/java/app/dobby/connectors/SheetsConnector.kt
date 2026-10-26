package app.dobby.connectors

import android.content.Context
import app.dobby.mcp.ToolSpec
import org.json.JSONObject
import java.net.URLEncoder

class SheetsConnector(private val context: Context, private val auth: GoogleAuth) : Connector {
    override val id = "sheets"
    override val title = "Google Sheets"
    override val description = "Read cell values from a Google Sheet (read-only)."
    override val requirement = Requirement.GoogleOAuth(listOf(GoogleAuth.SCOPE_SHEETS))
    override fun isReady(context: Context) = auth.isSignedIn() && GoogleAuth.SCOPE_SHEETS in auth.grantedScopes()

    override fun tools() = listOf(
        ToolSpec("read", "Read a range from a Google Sheet, e.g. 'Sheet1!A1:F50'.",
            ToolSpec.schema(listOf(
                Triple("spreadsheet_id", "string", "Spreadsheet id from the URL"),
                Triple("range", "string", "A1 range. Default 'A1:Z100'")), listOf("spreadsheet_id"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject {
        require(tool == "read") { "unknown tool $tool" }
        val range = URLEncoder.encode(args.optString("range", "A1:Z100"), "UTF-8")
        val res = JSONObject(auth.get("https://sheets.googleapis.com/v4/spreadsheets/${args.getString("spreadsheet_id")}/values/$range"))
        return JSONObject().put("range", res.optString("range")).put("values", res.optJSONArray("values") ?: org.json.JSONArray())
    }
}
