package app.dobby.model

import org.json.JSONObject

/** One row of the model registry. Add models in assets/models.json or from the app. */
data class ModelEntry(
    val id: String,
    val name: String,
    val file: String,
    val url: String,
    val sizeGb: Double,
    val backend: String,          // "gpu" | "cpu"
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val license: String,
    val gated: Boolean,
    val custom: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("file", file).put("url", url)
        .put("size_gb", sizeGb).put("backend", backend).put("supports_tools", supportsTools)
        .put("supports_vision", supportsVision).put("license", license).put("gated", gated).put("custom", custom)

    companion object {
        fun fromJson(o: JSONObject, custom: Boolean = false) = ModelEntry(
            id = o.getString("id"), name = o.getString("name"), file = o.getString("file"), url = o.getString("url"),
            sizeGb = o.optDouble("size_gb", 0.0), backend = o.optString("backend", "gpu"),
            supportsTools = o.optBoolean("supports_tools", true), supportsVision = o.optBoolean("supports_vision", false),
            license = o.optString("license", ""), gated = o.optBoolean("gated", false), custom = o.optBoolean("custom", custom))
    }
}
