package app.dobby.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Registers one geofence per place. The OS watches the circles; the app wakes only on
 * enter/exit. No periodic location requests are made anywhere in the app.
 */
class GeofenceManager(private val context: Context) {
    private val client = LocationServices.getGeofencingClient(context)
    private val store = PlaceStore(context)

    fun hasForeground() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun registerAll() {
        if (!hasForeground() || !hasBackground()) return
        val places = store.all()
        client.removeGeofences(pending())
        if (places.isEmpty()) return
        val fences = places.map {
            Geofence.Builder().setRequestId(it.id).setCircularRegion(it.lat, it.lng, it.radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(60_000).build()
        }
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
        client.addGeofences(req, pending())
    }

    fun unregisterAll() { client.removeGeofences(pending()) }

    /** One fresh fix for "set this place to where I am now". */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Pair<Double, Double>? {
        if (!hasForeground()) return null
        val fused = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { l -> cont.resume(l?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    private fun pending(): PendingIntent {
        val i = Intent(context, GeofenceReceiver::class.java).setAction("app.dobby.GEOFENCE")
        return PendingIntent.getBroadcast(context, 1001, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }
}
