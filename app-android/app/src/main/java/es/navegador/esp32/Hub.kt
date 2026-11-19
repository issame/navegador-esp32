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

enum class ConnState(val label: String) {
    DESCONECTADO("Desconectado"),
    BUSCANDO("Esperando a la pantalla…"),
    CONECTANDO("Conectando…"),
    CONECTADO("Conectado"),
}

/** Estado compartido entre el lector de notificaciones, el servicio BLE y la interfaz. */
object Hub {
    enum class Request { SEND_TIME }

    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 1_000_000L
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _nav = MutableStateFlow(NavInfo.INACTIVE)
    val nav: StateFlow<NavInfo> = _nav.asStateFlow()

    private val _conn = MutableStateFlow(ConnState.DESCONECTADO)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _lastTime = MutableStateFlow<String?>(null)
    val lastTime: StateFlow<String?> = _lastTime.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    private val _testActive = MutableStateFlow(false)
    val testActive: StateFlow<Boolean> = _testActive.asStateFlow()

    private var logFile: File? = null

    fun init(dir: File) {
        logFile = File(dir, "registro.txt")
    }

    fun setNav(n: NavInfo) { _nav.value = n }
    fun setConn(c: ConnState) { _conn.value = c }
    fun setLastTime(s: String) { _lastTime.value = s }
    fun setTestActive(active: Boolean) { _testActive.value = active }
    fun request(r: Request) { _requests.tryEmit(r) }

    @Synchronized
    fun log(message: String) {
        val line = LocalTime.now().format(CLOCK) + "  " + message
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        logFile?.let { f ->
            runCatching {
                if (f.length() > MAX_FILE_BYTES) f.delete()
                f.appendText(line + "\n")
            }
        }
        Log.d("Navegador", message)
    }

    @Synchronized
    fun clearLog() {
        _lines.value = emptyList()
        logFile?.delete()
    }

    fun logFileOrNull(): File? = logFile?.takeIf { it.exists() }
}
