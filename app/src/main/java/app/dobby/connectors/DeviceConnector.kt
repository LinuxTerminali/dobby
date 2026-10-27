package app.dobby.connectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.dobby.mcp.ToolSpec
import app.dobby.util.Fmt
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small device facts: time and last known location. */
class DeviceConnector(private val context: Context) : Connector {
    override val id = "device"
    override val title = "Device"
    override val description = "Current time and last known location."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    override fun isReady(context: Context) = true  // time works without location permission

    override fun tools() = listOf(
        ToolSpec("now", "Current date and time on the phone.", ToolSpec.schema(emptyList())),
        ToolSpec("location", "Last known location (lat, lng). Needs location permission.", ToolSpec.schema(emptyList())),
    )

    @SuppressLint("MissingPermission")
    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "now" -> { val ms = System.currentTimeMillis(); JSONObject().put("summary", "It is ${Fmt.dateTime(ms)} (${Fmt.date(ms)}).").put("now", SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))) }
        "location" -> {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                JSONObject().put("error", "location permission not granted")
            else {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
                if (loc == null) JSONObject().put("error", "no last known location")
                else JSONObject().put("summary", "Last known location: latitude %.5f, longitude %.5f, %d min ago.".format(loc.latitude, loc.longitude, (System.currentTimeMillis() - loc.time) / 60000))
                    .put("lat", loc.latitude).put("lng", loc.longitude).put("age_min", (System.currentTimeMillis() - loc.time) / 60000)
            }
        }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }
}
