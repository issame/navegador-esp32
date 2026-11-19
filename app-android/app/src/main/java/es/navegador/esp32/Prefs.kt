package es.navegador.esp32

import android.content.Context

/** Pantalla emparejada (dirección Bluetooth y nombre), guardada entre usos de la app. */
object Prefs {
    private const val FILE_NAME = "ajustes"
    private const val KEY_DEVICE_ADDRESS = "direccion"
    private const val KEY_DEVICE_NAME = "nombre"

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun deviceAddress(context: Context): String? =
        preferences(context).getString(KEY_DEVICE_ADDRESS, null)

    fun deviceName(context: Context): String? =
        preferences(context).getString(KEY_DEVICE_NAME, null)

    fun saveDevice(context: Context, address: String, name: String?) {
        preferences(context).edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .apply()
    }

    fun forgetDevice(context: Context) {
        preferences(context).edit().clear().apply()
    }
}
