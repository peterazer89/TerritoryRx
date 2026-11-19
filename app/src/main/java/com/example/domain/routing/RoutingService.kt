package com.example.domain.routing

import com.example.BuildConfig
import com.example.data.local.TerritoryDao
import com.example.data.model.RouteCacheEntity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 5-Tier Traffic & Road Travel Estimate Hierarchy (Section 11.3):
 * 1. LIVE_TRAFFIC: Google Maps Platform Routes API with TRAFFIC_AWARE
 * 2. FRESH_CACHED_ROAD: Cached road estimate < 30 minutes old
 * 3. STALE_CACHED_ROAD: Cached road estimate > 30 minutes old (with visible age label)
 * 4. TYPICAL_ROAD: Urban road network tortuosity model (1.38x road factor + corridor congestion profile)
 * 5. APPROXIMATE_HAVERSINE: Straight-line approximation when road model is bypassed
 */
enum class TrafficEstimateSource(val badgeLabel: String, val isLive: Boolean) {
    SAME_LOCATION_SEQUENTIAL("Same Building / Sequential (0m)", false),
    LIVE_TRAFFIC("Live Traffic (Routes API)", true),
    FRESH_CACHED_ROAD("Cached Road (<30m)", false),
    STALE_CACHED_ROAD("Stale Road Cache (>30m)", false),
    TYPICAL_ROAD("Typical Urban Road Estimate (Offline)", false),
    APPROXIMATE_HAVERSINE("Approximate Distance Fallback", false)
}

data class TravelEstimate(
    val baseDurationMinutes: Int,
    val bufferedDurationMinutes: Int,
    val distanceKm: Double,
    val source: TrafficEstimateSource,
    val lastUpdatedLabel: String
)

interface RoutingProvider {
    suspend fun estimateTravel(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        departureMinuteOfDay: Int,
        safetyBufferPercent: Int,
        allowNetworkCall: Boolean = false
    ): TravelEstimate
}

class DefaultRoutingService(
    private val dao: TerritoryDao? = null
) : RoutingProvider {

    private val memoryCache = ConcurrentHashMap<String, RouteCacheEntity>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun estimateTravel(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        departureMinuteOfDay: Int,
        safetyBufferPercent: Int,
        allowNetworkCall: Boolean
    ): TravelEstimate {
        val haversineKm = haversineDistanceKm(originLat, originLng, destLat, destLng)
        if (haversineKm < 0.05) {
            return TravelEstimate(
                baseDurationMinutes = 0,
                bufferedDurationMinutes = 0,
                distanceKm = 0.0,
                source = TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL,
                lastUpdatedLabel = "Same building / sequential visit (0m transition)"
            )
        }

        val timeBucket = (departureMinuteOfDay / 60).coerceIn(0, 23)
        val cacheKey = String.format(
            Locale.US,
            "%.4f,%.4f->%.4f,%.4f@h%d",
            originLat, originLng, destLat, destLng, timeBucket
        )

        val nowMs = System.currentTimeMillis()

        // Fast-path: check in-memory cache first (avoids 40,000+ SQLite queries during route optimization)
        val memCached = memoryCache[cacheKey]
        if (memCached != null) {
            val ageMinutes = ((nowMs - memCached.updatedAtEpochMs) / 60_000L).toInt()
            if (ageMinutes <= 30 || !allowNetworkCall) {
                val buffered = applyBuffer(memCached.travelMinutes, safetyBufferPercent)
                return TravelEstimate(
                    baseDurationMinutes = memCached.travelMinutes,
                    bufferedDurationMinutes = buffered,
                    distanceKm = memCached.distanceKm,
                    source = if (ageMinutes <= 30) TrafficEstimateSource.FRESH_CACHED_ROAD else TrafficEstimateSource.TYPICAL_ROAD,
                    lastUpdatedLabel = "Cached ${ageMinutes}m ago"
                )
            }
        }

        // Only query SQLite Room cache or live network when allowNetworkCall is explicitly enabled
        var dbCached: RouteCacheEntity? = null
        if (allowNetworkCall && dao != null) {
            dbCached = dao.getRouteCache(cacheKey)
            if (dbCached != null) {
                memoryCache[cacheKey] = dbCached
                val ageMinutes = ((nowMs - dbCached.updatedAtEpochMs) / 60_000L).toInt()
                if (ageMinutes <= 30) {
                    val buffered = applyBuffer(dbCached.travelMinutes, safetyBufferPercent)
                    return TravelEstimate(
                        baseDurationMinutes = dbCached.travelMinutes,
                        bufferedDurationMinutes = buffered,
                        distanceKm = dbCached.distanceKm,
                        source = TrafficEstimateSource.FRESH_CACHED_ROAD,
                        lastUpdatedLabel = "Cached ${ageMinutes}m ago"
                    )
                }
            }

            val mapsKey = try {
                BuildConfig.GOOGLE_MAPS_API_KEY
            } catch (_: Exception) {
                ""
            }
            val hasValidMapsKey = mapsKey.isNotBlank() &&
                mapsKey != "YOUR_GOOGLE_MAPS_API_KEY" &&
                !mapsKey.startsWith("MY_")

            if (hasValidMapsKey) {
                val liveResult = fetchGoogleRoutesEstimate(originLat, originLng, destLat, destLng, mapsKey)
                if (liveResult != null) {
                    val entry = RouteCacheEntity(
                        cacheKey = cacheKey,
                        originLat = originLat,
                        originLng = originLng,
                        destLat = destLat,
                        destLng = destLng,
                        travelMinutes = liveResult.first,
                        distanceKm = liveResult.second,
                        sourceType = TrafficEstimateSource.LIVE_TRAFFIC.name,
                        updatedAtEpochMs = nowMs
                    )
                    memoryCache[cacheKey] = entry
                    dao.insertRouteCache(entry)
                    val buffered = applyBuffer(liveResult.first, safetyBufferPercent)
                    return TravelEstimate(
                        baseDurationMinutes = liveResult.first,
                        bufferedDurationMinutes = buffered,
                        distanceKm = liveResult.second,
                        source = TrafficEstimateSource.LIVE_TRAFFIC,
                        lastUpdatedLabel = "Live traffic just now"
                    )
                }
            }

            if (dbCached != null) {
                val ageMinutes = ((nowMs - dbCached.updatedAtEpochMs) / 60_000L).toInt()
                val buffered = applyBuffer(dbCached.travelMinutes, safetyBufferPercent)
                return TravelEstimate(
                    baseDurationMinutes = dbCached.travelMinutes,
                    bufferedDurationMinutes = buffered,
                    distanceKm = dbCached.distanceKm,
                    source = TrafficEstimateSource.STALE_CACHED_ROAD,
                    lastUpdatedLabel = "Stale cache (${ageMinutes}m old)"
                )
            }
        }

        // Tier 4: Typical urban road travel estimate (accounts for 1.38x road network tortuosity + time-of-day speed)
        val roadDistanceKm = ((haversineKm * 1.38) * 100.0).roundToInt() / 100.0
        val speedKmh = when (departureMinuteOfDay) {
            in 450..600 -> 26.0  // Morning rush
            in 780..1140 -> 22.0 // Afternoon/Evening peak
            else -> 34.0         // Off-peak
        }
        val intersectionDelayMin = 4
        val baseRoadMinutes = max(5, ((roadDistanceKm / speedKmh) * 60.0).roundToInt() + intersectionDelayMin)
        val bufferedMinutes = applyBuffer(baseRoadMinutes, safetyBufferPercent)

        memoryCache[cacheKey] = RouteCacheEntity(
            cacheKey = cacheKey,
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            travelMinutes = baseRoadMinutes,
            distanceKm = roadDistanceKm,
            sourceType = TrafficEstimateSource.TYPICAL_ROAD.name,
            updatedAtEpochMs = nowMs
        )

        return TravelEstimate(
            baseDurationMinutes = baseRoadMinutes,
            bufferedDurationMinutes = bufferedMinutes,
            distanceKm = roadDistanceKm,
            source = TrafficEstimateSource.TYPICAL_ROAD,
            lastUpdatedLabel = "Typical road profile (Offline)"
        )
    }

    private suspend fun fetchGoogleRoutesEstimate(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        apiKey: String
    ): Pair<Int, Double>? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("origin", JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", originLat).put("longitude", originLng))))
                put("destination", JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", destLat).put("longitude", destLng))))
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
            }
            val request = Request.Builder()
                .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader("X-Goog-FieldMask", "routes.duration,routes.distanceMeters")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val raw = response.body?.string() ?: return@withContext null
                val json = JSONObject(raw)
                val routes = json.optJSONArray("routes") ?: return@withContext null
                if (routes.length() == 0) return@withContext null
                val first = routes.getJSONObject(0)
                val distMeters = first.optDouble("distanceMeters", 0.0)
                val durationStr = first.optString("duration", "0s").removeSuffix("s")
                val durationSec = durationStr.toDoubleOrNull() ?: 0.0
                if (distMeters <= 0.0 || durationSec <= 0.0) return@withContext null
                val mins = max(1, (durationSec / 60.0).roundToInt())
                val km = ((distMeters / 1000.0) * 100.0).roundToInt() / 100.0
                mins to km
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyBuffer(baseMinutes: Int, bufferPercent: Int): Int {
        if (bufferPercent <= 0) return baseMinutes
        return max(baseMinutes, (baseMinutes * (1.0 + bufferPercent / 100.0)).roundToInt())
    }

    companion object {
        fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
