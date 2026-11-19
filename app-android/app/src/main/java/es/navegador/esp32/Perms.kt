package es.navegador.esp32

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Permisos que necesita la app y comprobación de si están concedidos. */
object Perms {

    /** Permisos de Bluetooth: cambiaron en Android 12 (antes había que pedir ubicación). */
    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Todo lo que se pide en tiempo de ejecución (Bluetooth y, en Android 13+, notificaciones). */
    fun runtimePermissions(): Array<String> = buildList {
        addAll(bluetoothPermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS) // notificación fija de la conexión
        }
    }.toTypedArray()

    fun hasBluetooth(context: Context): Boolean = bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** El "Acceso a notificaciones" se concede en Ajustes, no con un diálogo. */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Algunos fabricantes cierran las apps en segundo plano para ahorrar batería, lo que
     * cortaría la conexión con la placa. true si la app está excluida de ese ahorro.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
}
