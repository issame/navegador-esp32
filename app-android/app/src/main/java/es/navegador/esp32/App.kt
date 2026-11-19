package es.navegador.esp32

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(filesDir)
    }
}

/** Pantalla emparejada. */
object Prefs {
    private const val FILE = "ajustes"
    private const val KEY_ADDRESS = "direccion"
    private const val KEY_NAME = "nombre"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceAddress(ctx: Context): String? = prefs(ctx).getString(KEY_ADDRESS, null)
    fun deviceName(ctx: Context): String? = prefs(ctx).getString(KEY_NAME, null)

    fun saveDevice(ctx: Context, address: String, name: String?) {
        prefs(ctx).edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}

object Perms {
    /** Permisos que hay que pedir en tiempo de ejecución. */
    fun runtime(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun hasBluetooth(ctx: Context): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun hasNotificationAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
