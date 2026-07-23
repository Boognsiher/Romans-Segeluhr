package com.segeluhr.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*
import com.segeluhr.app.core.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Liefert GPS-Fixe des Handys als Flow<Fix>, 1 Hz. Analog zu
 * navigator.geolocation.watchPosition() im Browser-Prototyp:
 * - cog == null, wenn kein brauchbarer heading verfügbar
 * - sogKn wird aus m/s in Knoten umgerechnet (Faktor 1.943844), da die
 *   gesamte Spezifikation (Abschnitt 9/10) in Knoten rechnet.
 */
class LocationProvider(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun fixFlow(): Flow<Fix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val hasHeading = loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.1f
                val fix = Fix(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    cogDeg = if (hasHeading) loc.bearing.toDouble() else null,
                    sogKn = if (loc.hasSpeed()) loc.speed.toDouble() * 1.943844 else 0.0,
                    timestampMs = System.currentTimeMillis(),
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    valid = true,
                )
                trySend(fix)
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
