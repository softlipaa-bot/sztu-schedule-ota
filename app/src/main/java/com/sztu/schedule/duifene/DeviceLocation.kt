package com.sztu.schedule.duifene

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 定位签到只用用户授权后的设备位置。
 * 不使用教室坐标，也不做随机偏移。
 */
class DeviceLocation(private val context: Context) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun resume() {
        cancelled = false
    }

    fun permissionState(): String {
        val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine || coarse -> "granted"
            else -> "denied"
        }
    }

    fun fix(): GeoPoint? {
        if (permissionState() != "granted" || cancelled) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val recent = newest(manager)
        if (recent != null && usable(recent)) return recent.toPoint()
        return awaitCurrent(manager)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun newest(manager: LocationManager): Location? {
        val names = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
        var best: Location? = null
        for (name in names) {
            val enabled = try {
                manager.isProviderEnabled(name)
            } catch (_: Exception) {
                false
            }
            if (!enabled) continue
            val location = try {
                manager.getLastKnownLocation(name)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (best == null || location.time > best.time) best = location
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private fun awaitCurrent(manager: LocationManager): GeoPoint? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || cancelled) return null
        val provider = when {
            tryEnable(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            tryEnable(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val latch = CountDownLatch(1)
        val box = AtomicReference<Location?>()
        try {
            manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                box.set(location)
                latch.countDown()
            }
        } catch (_: Exception) {
            return null
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!cancelled && System.nanoTime() < deadline) {
            if (latch.await(200, TimeUnit.MILLISECONDS)) break
        }
        val location = box.get() ?: return null
        return if (usable(location)) location.toPoint() else null
    }

    private fun tryEnable(manager: LocationManager, provider: String): Boolean =
        try {
            manager.isProviderEnabled(provider)
        } catch (_: Exception) {
            false
        }

    private fun usable(location: Location): Boolean {
        val age = System.currentTimeMillis() - location.time
        if (age > 120_000) return false
        if (location.hasAccuracy() && location.accuracy > 100f) return false
        if (location.latitude == 0.0 && location.longitude == 0.0) return false
        return true
    }

    private fun Location.toPoint() = GeoPoint(latitude, longitude)
}
