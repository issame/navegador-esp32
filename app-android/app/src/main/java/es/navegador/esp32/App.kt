package es.navegador.esp32

import android.app.Application

/** Punto de entrada de la app: prepara el registro antes que nada. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(filesDir)
    }
}
