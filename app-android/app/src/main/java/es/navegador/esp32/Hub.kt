package es.navegador.esp32

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Estado de la conexión con la placa, con el texto que se muestra al usuario. */
enum class ConnectionState(val label: String) {
    DISCONNECTED("Desconectado"),
    WAITING_FOR_DEVICE("Esperando a la pantalla…"),
    CONNECTING("Conectando…"),
    CONNECTED("Conectado"),
}

/**
 * Punto de encuentro entre las tres partes de la app, que viven en el mismo proceso:
 *  - [MapsListener] publica la navegación leída de Google Maps.
 *  - [BleService] la envía a la placa y publica el estado de la conexión.
 *  - [MainActivity] y [LogActivity] lo muestran.
 *
 * También guarda el registro (en memoria y en un archivo) para poder revisar después
 * qué llegó de Maps y qué se envió.
 */
object Hub {
    /** Peticiones de la interfaz al servicio BLE. */
    enum class Request { SEND_TIME }

    private const val MAX_LOG_LINES = 500
    private const val MAX_LOG_FILE_BYTES = 1_000_000L
    private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _nav = MutableStateFlow(NavInfo.INACTIVE)
    /** Navegación actual (de Maps o de la prueba). */
    val nav: StateFlow<NavInfo> = _nav.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _lastTimeSent = MutableStateFlow<String?>(null)
    /** Hora ("HH:mm:ss") del último envío de la hora a la placa. */
    val lastTimeSent: StateFlow<String?> = _lastTimeSent.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    private val _testActive = MutableStateFlow(false)
    /** true mientras se muestra la ruta de prueba. */
    val testActive: StateFlow<Boolean> = _testActive.asStateFlow()

    private var logFile: File? = null

    /** Se llama al arrancar la app ([App]) con su carpeta privada de archivos. */
    fun init(filesDir: File) {
        logFile = File(filesDir, "registro.txt")
    }

    fun setNav(nav: NavInfo) { _nav.value = nav }
    fun setConnection(state: ConnectionState) { _connection.value = state }
    fun setLastTimeSent(time: String) { _lastTimeSent.value = time }
    fun setTestActive(active: Boolean) { _testActive.value = active }
    fun request(request: Request) { _requests.tryEmit(request) }

    /** Añade una línea con la hora al registro en memoria, al archivo y a logcat. */
    @Synchronized
    fun log(message: String) {
        val line = LocalTime.now().format(LOG_TIME_FORMAT) + "  " + message
        _logLines.value = (_logLines.value + line).takeLast(MAX_LOG_LINES)
        logFile?.let { file ->
            runCatching {
                if (file.length() > MAX_LOG_FILE_BYTES) file.delete() // evita que crezca sin fin
                file.appendText(line + "\n")
            }
        }
        Log.d("Navegador", message)
    }

    @Synchronized
    fun clearLog() {
        _logLines.value = emptyList()
        logFile?.delete()
    }

    /** Archivo del registro, si existe (incluye lo de sesiones anteriores). */
    fun logFileIfExists(): File? = logFile?.takeIf { it.exists() }
}
