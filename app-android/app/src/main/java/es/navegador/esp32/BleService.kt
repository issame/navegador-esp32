package es.navegador.esp32

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Servicio en primer plano que mantiene la conexión BLE con la placa y le envía:
 *  - la fecha y la hora al conectar, cada hora y cuando cambia la hora del móvil;
 *  - la navegación cada vez que cambia [Hub.nav] (y el aviso de llegada);
 *  - la batería del móvil al conectar y cuando cambia;
 *  - el firmware nuevo cuando el usuario elige actualizarla ([FirmwareUpdateProtocol]).
 *
 * Si se pierde la conexión, se reconecta solo. Todo el trabajo se hace en el hilo
 * principal (las respuestas de Bluetooth llegan en otro hilo y se pasan aquí).
 */
@SuppressLint("MissingPermission") // los permisos se comprueban en connect()
class BleService : Service() {

    companion object {
        /** Servicio y característica de escritura de la librería ChronosESP32 (tipo Nordic UART). */
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        private const val NOTIFICATION_CHANNEL_ID = "conexion"
        private const val NOTIFICATION_ID = 1

        /** MTU máxima que se pide; con ella casi todos los paquetes caben en una escritura. */
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_MTU = 23

        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val UPDATE_START_TIMEOUT_MS = 10_000L
        private const val UPDATE_FINISH_TIMEOUT_MS = 20_000L

        /** Descriptor estándar para activar los avisos (notificaciones) de una característica. */
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIME_RESYNC_INTERVAL_MS = 3_600_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleService::class.java))
        }
    }

    /**
     * Si algo falla de forma inesperada en una tarea del servicio, se anota en el registro
     * en lugar de cerrar la app; la conexión y el resto de tareas siguen funcionando.
     */
    private val errorHandler = CoroutineExceptionHandler { _, error ->
        Hub.log("Error inesperado: ${error::class.simpleName}: ${error.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)

    /** Trozos pendientes de escribir; BLE solo admite una escritura a la vez. */
    private val pendingWrites = Channel<ByteArray>(Channel.UNLIMITED)

    private var currentGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /** Características de actualización; null si el firmware de la placa no la admite. */
    private var updateControlCharacteristic: BluetoothGattCharacteristic? = null
    private var updateDataCharacteristic: BluetoothGattCharacteristic? = null

    /** Último estado de actualización que ha avisado la placa. */
    private val boardUpdateStatus = MutableStateFlow<FirmwareUpdateProtocol.BoardStatus?>(null)

    /** Android solo admite una operación BLE a la vez: todas las escrituras pasan por aquí. */
    private val gattLock = Mutex()
    private var negotiatedMtu = DEFAULT_MTU

    /** true cuando la placa está conectada y lista para recibir. */
    private var isReady = false

    /** true mientras el servicio se está cerrando: no hay que reconectar. */
    private var isStopping = false

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    /** Última batería del móvil leída (porcentaje y si carga), para enviarla solo si cambia. */
    private var phoneBatteryPercent = -1
    private var phoneCharging = false

    /** CRC de la última flecha enviada, para no reenviarla si no cambia. */
    private var lastSentIconCrc: Long? = null

    /** Se completa cuando Android confirma la escritura en curso. */
    @Volatile
    private var writeConfirmation: CompletableDeferred<Boolean>? = null

    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Hub.log("Cambio de hora en el móvil")
            sendTime()
        }
    }

    /** Android avisa cada vez que cambia la batería (nivel o cargador). */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (percent == phoneBatteryPercent && charging == phoneCharging) return
            phoneBatteryPercent = percent
            phoneCharging = charging
            sendPhoneBattery()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        ContextCompat.registerReceiver(
            this,
            timeChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Es un aviso "pegajoso": al registrarlo llega enseguida el estado actual.
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { for (chunk in pendingWrites) writeChunk(chunk) }
        scope.launch { Hub.nav.collect { sendNavigation(it) } }
        scope.launch { Hub.requests.collect { if (it == Hub.Request.SEND_TIME) sendTime() } }
        scope.launch { Hub.connection.collect { updateNotification(it) } }
        scope.launch { Hub.firmwareUpdateRequests.collect { updateFirmware(it) } }
        scope.launch {
            // La placa no tiene reloj con pila: se resincroniza cada hora por si se desvía.
            while (isActive) {
                delay(TIME_RESYNC_INTERVAL_MS)
                sendTime()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (currentGatt == null && reconnectJob?.isActive != true) connect()
        return START_STICKY // si Android cierra el servicio, que lo vuelva a arrancar
    }

    override fun onDestroy() {
        isStopping = true
        scope.cancel()
        runCatching { unregisterReceiver(timeChangeReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        currentGatt?.let {
            it.disconnect()
            it.close()
        }
        currentGatt = null
        isReady = false
        Hub.setConnection(ConnectionState.DISCONNECTED)
        Hub.log("Conexión detenida")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- conexión

    private fun connect() {
        if (isStopping) return
        val address = Prefs.deviceAddress(this)
        if (address == null) {
            Hub.log("No hay ninguna pantalla emparejada")
            stopSelf()
            return
        }
        if (!Perms.hasBluetooth(this)) {
            Hub.log("Faltan permisos de Bluetooth")
            stopSelf()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Hub.log("El Bluetooth está apagado; se reintentará")
            Hub.setConnection(ConnectionState.WAITING_FOR_DEVICE)
            scheduleReconnect()
            return
        }
        // El primer intento es directo. Los siguientes usan autoConnect: Android espera
        // a que la placa aparezca y conecta en cuanto la ve, sin gastar batería reintentando.
        val waitForDevice = reconnectAttempts > 0
        Hub.setConnection(if (waitForDevice) ConnectionState.WAITING_FOR_DEVICE else ConnectionState.CONNECTING)
        Hub.log("Conectando con $address" + if (waitForDevice) " (en espera)" else "")
        currentGatt = adapter.getRemoteDevice(address)
            .connectGatt(this, waitForDevice, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Reintenta con espera creciente: 2 s, 4 s, 6 s… hasta 30 s. */
    private fun scheduleReconnect() {
        if (isStopping) return
        reconnectAttempts++
        val delayMs = minOf(MAX_RECONNECT_DELAY_MS, 2_000L * reconnectAttempts)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun onConnectionLost(gatt: BluetoothGatt) {
        isReady = false
        rxCharacteristic = null
        updateControlCharacteristic = null
        updateDataCharacteristic = null
        while (pendingWrites.tryReceive().isSuccess) { /* descarta lo que quedaba por enviar */ }
        writeConfirmation?.complete(false)
        gatt.close()
        if (currentGatt === gatt) currentGatt = null
        if (isStopping) return
        Hub.setConnection(ConnectionState.WAITING_FOR_DEVICE)
        scheduleReconnect()
    }

    /**
     * Respuestas de Android sobre la conexión. Llegan en un hilo de Bluetooth, así que
     * cada una se pasa al hilo principal con scope.launch.
     * Orden al conectar: conectado → MTU negociada → servicios descubiertos → lista.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    reconnectAttempts = 0
                    Hub.log("Conectado; ajustando tamaño de paquete")
                    if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                } else {
                    Hub.log("Desconectado (código $status)")
                    onConnectionLost(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
                Hub.log("Tamaño de paquete: $negotiatedMtu bytes")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHARACTERISTIC_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                    Hub.log("La placa no tiene el servicio esperado")
                    gatt.disconnect()
                    return@launch
                }
                rxCharacteristic = characteristic

                // Servicio de actualización (solo en firmwares que lo incluyen).
                val updateService = gatt.getService(FirmwareUpdateProtocol.SERVICE_UUID)
                updateControlCharacteristic = updateService?.getCharacteristic(FirmwareUpdateProtocol.CONTROL_UUID)
                updateDataCharacteristic = updateService?.getCharacteristic(FirmwareUpdateProtocol.DATA_UUID)
                val control = updateControlCharacteristic
                if (control != null && enableNotifications(gatt, control)) {
                    return@launch // se termina en onDescriptorWrite
                }
                onBoardReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) Hub.log("No se pudieron activar los avisos de actualización")
                onBoardReady()
            }
        }

        // Avisos de la placa (estado de la actualización). Android 13+ usa la primera
        // versión; las anteriores, la segunda.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBoardNotification(characteristic, value)
        }

        @Deprecated("Android 12 y anteriores")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onBoardNotification(characteristic, characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeConfirmation?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** La placa está lista: se marca como conectada y se le envía todo lo actual. */
    private fun onBoardReady() {
        isReady = true
        lastSentIconCrc = null // la placa puede haberse reiniciado: reenviar la flecha
        Hub.setConnection(ConnectionState.CONNECTED)
        Hub.log(
            if (updateControlCharacteristic != null) "Pantalla lista (admite actualizar por Bluetooth)"
            else "Pantalla lista"
        )
        sendTime()
        sendPhoneBattery()
        sendNavigation(Hub.nav.value)
    }

    private fun onBoardNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (characteristic.uuid != FirmwareUpdateProtocol.CONTROL_UUID || value == null) return
        FirmwareUpdateProtocol.parseStatus(value)?.let { boardUpdateStatus.value = it }
    }

    /** Pide a la placa que avise cuando cambie [characteristic]. Devuelve false si no se pudo. */
    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID) ?: return false
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = enable
            gatt.writeDescriptor(descriptor)
        }
    }

    // ---------------------------------------------------------------- envío

    private fun sendTime() {
        if (!isReady) return
        val now = LocalDateTime.now()
        enqueue(Protocol.timePacket(now))
        val formatted = now.format(LOG_TIME_FORMAT)
        Hub.setLastTimeSent(formatted)
        Hub.log("→ hora $formatted")
    }

    private fun sendPhoneBattery() {
        if (!isReady || phoneBatteryPercent < 0) return
        enqueue(Protocol.phoneBatteryPacket(phoneBatteryPercent, phoneCharging))
        Hub.log("→ batería del móvil $phoneBatteryPercent %" + if (phoneCharging) " (cargando)" else "")
    }

    private fun sendNavigation(nav: NavInfo) {
        if (!isReady) return
        if (nav.arrived) {
            enqueue(Protocol.navigationPacket(nav, 0xFFFFFFFFL))
            lastSentIconCrc = null
            Hub.log("→ has llegado")
            return
        }
        if (!nav.active) {
            enqueue(Protocol.navigationEndedPacket())
            lastSentIconCrc = null
            Hub.log("→ navegación terminada")
            return
        }
        val icon = nav.icon
        val iconCrc = icon?.let(Protocol::iconCrc) ?: 0xFFFFFFFFL
        enqueue(Protocol.navigationPacket(nav, iconCrc))
        Hub.log("→ navegación: ${nav.maneuverDistance} | ${nav.instruction.replace('\n', ' ')} | ${nav.summary}")
        // La flecha (3 paquetes) solo se envía cuando cambia.
        if (icon != null && iconCrc != lastSentIconCrc) {
            Protocol.iconPackets(icon, iconCrc).forEach(::enqueue)
            lastSentIconCrc = iconCrc
            Hub.log("→ flecha nueva")
        }
    }

    /** Trocea el paquete según la MTU y lo pone en la cola de escritura. */
    private fun enqueue(packet: ByteArray) {
        // Durante una actualización la placa solo atiende al firmware nuevo; al reiniciarse
        // se reconecta y se le reenvía todo.
        if (!isReady || Hub.firmwareUpdateRunning.value) return
        Protocol.split(packet, Protocol.maxWriteBytes(negotiatedMtu)).forEach { pendingWrites.trySend(it) }
    }

    /** Escribe un trozo de la cola de datos (hora y navegación). */
    private suspend fun writeChunk(chunk: ByteArray) {
        val characteristic = rxCharacteristic ?: return
        writeAndWait(characteristic, chunk)
    }

    /**
     * Escribe [data] en [characteristic] y espera a que la placa lo confirme.
     * Devuelve false si no se pudo escribir o no llegó la confirmación.
     */
    private suspend fun writeAndWait(characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        gattLock.withLock {
            val gatt = currentGatt
            if (!isReady || gatt == null) return@withLock false
            val confirmation = CompletableDeferred<Boolean>()
            writeConfirmation = confirmation
            val started = runCatching { startWrite(gatt, characteristic, data) }
                .onFailure { Hub.log("Error al escribir ${data.size} B: ${it.message}") }
                .getOrDefault(false)
            if (!started) {
                Hub.log("No se pudo enviar un paquete (${data.size} B)")
                return@withLock false
            }
            val confirmed = withTimeoutOrNull(WRITE_TIMEOUT_MS) { confirmation.await() } == true
            if (!confirmed) Hub.log("La pantalla no confirmó un paquete")
            confirmed
        }

    // ---------------------------------------------------------------- actualización del firmware

    /**
     * Envía el firmware de [file] a la placa: orden de empezar, el archivo en trozos y orden
     * de terminar. La placa lo comprueba (MD5) y se reinicia con él; si algo falla, sigue
     * con el firmware anterior.
     */
    private suspend fun updateFirmware(file: Uri) {
        if (Hub.firmwareUpdateRunning.value) return
        val control = updateControlCharacteristic
        val data = updateDataCharacteristic
        if (!isReady || control == null || data == null) {
            reportUpdate(
                if (!isReady) "La pantalla no está conectada"
                else "El firmware de la placa no admite actualizar por Bluetooth. Grábalo una vez por USB."
            )
            return
        }

        val firmware = withContext(Dispatchers.IO) {
            runCatching { contentResolver.openInputStream(file)?.use { it.readBytes() } }.getOrNull()
        }
        if (firmware == null) {
            reportUpdate("No se pudo leer el archivo")
            return
        }
        FirmwareUpdateProtocol.validate(firmware)?.let { problem ->
            reportUpdate("No se puede usar este archivo: $problem")
            return
        }

        Hub.setFirmwareUpdateRunning(true)
        currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) // más rápido
        try {
            sendFirmware(firmware, control, data)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            reportUpdate("Error inesperado (${error.message}). La placa sigue con el firmware anterior.")
            runCatching { writeAndWait(control, byteArrayOf(FirmwareUpdateProtocol.COMMAND_CANCEL.toByte())) }
        } finally {
            Hub.setFirmwareUpdateRunning(false)
            currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
    }

    private suspend fun sendFirmware(
        firmware: ByteArray,
        control: BluetoothGattCharacteristic,
        data: BluetoothGattCharacteristic,
    ) {
        val sizeKb = firmware.size / 1024
        reportUpdate("Preparando la placa ($sizeKb KB)…")
        boardUpdateStatus.value = null
        val start = FirmwareUpdateProtocol.startCommand(firmware.size, FirmwareUpdateProtocol.md5(firmware))
        if (!writeAndWait(control, start)) {
            reportUpdate("No se pudo empezar la actualización")
            return
        }
        val startStatus = withTimeoutOrNull(UPDATE_START_TIMEOUT_MS) {
            boardUpdateStatus.first { it != null && it.state != FirmwareUpdateProtocol.BoardState.IDLE }
        }
        if (startStatus?.state != FirmwareUpdateProtocol.BoardState.RECEIVING) {
            reportFailure(startStatus)
            return
        }

        // El archivo en trozos del tamaño máximo que admite la conexión (512 bytes como mucho).
        val chunkBytes = Protocol.maxWriteBytes(negotiatedMtu)
        var offset = 0
        var lastPercent = -1
        while (offset < firmware.size) {
            if (boardUpdateStatus.value?.state == FirmwareUpdateProtocol.BoardState.FAILED) {
                reportFailure(boardUpdateStatus.value)
                return
            }
            val end = minOf(offset + chunkBytes, firmware.size)
            if (!writeAndWait(data, firmware.copyOfRange(offset, end))) {
                writeAndWait(control, byteArrayOf(FirmwareUpdateProtocol.COMMAND_CANCEL.toByte()))
                reportUpdate("Se interrumpió el envío. La placa sigue con el firmware anterior.")
                return
            }
            offset = end
            val percent = offset * 100 / firmware.size
            if (percent != lastPercent) {
                lastPercent = percent
                Hub.setFirmwareUpdateStatus("Enviando firmware: $percent %")
            }
        }

        writeAndWait(control, byteArrayOf(FirmwareUpdateProtocol.COMMAND_FINISH.toByte()))
        val finalStatus = withTimeoutOrNull(UPDATE_FINISH_TIMEOUT_MS) {
            boardUpdateStatus.first {
                it?.state == FirmwareUpdateProtocol.BoardState.COMPLETED ||
                    it?.state == FirmwareUpdateProtocol.BoardState.FAILED
            }
        }
        if (finalStatus?.state == FirmwareUpdateProtocol.BoardState.COMPLETED) {
            reportUpdate("Firmware actualizado. La placa se reinicia y se volverá a conectar sola.")
        } else {
            reportFailure(finalStatus)
        }
    }

    private fun reportFailure(status: FirmwareUpdateProtocol.BoardStatus?) {
        val reason = if (status == null) "la placa no respondió"
        else FirmwareUpdateProtocol.errorDescription(status.errorCode)
        reportUpdate("La actualización falló: $reason. La placa sigue con el firmware anterior.")
    }

    private fun reportUpdate(message: String) {
        Hub.setFirmwareUpdateStatus(message)
        Hub.log("Actualización: $message")
    }

    /** La forma de escribir cambió en Android 13; se usa la que toque. */
    @Suppress("DEPRECATION")
    private fun startWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
        }

    // ---------------------------------------------------------------- notificación fija

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.canal_conexion),
            NotificationManager.IMPORTANCE_LOW, // sin sonido
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deviceName = Prefs.deviceName(this) ?: getString(R.string.pantalla)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_map)
            .setContentTitle(deviceName)
            .setContentText(state.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    /** Android exige que un servicio en primer plano muestre una notificación desde el inicio. */
    private fun startInForeground() {
        val notification = buildNotification(Hub.connection.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ConnectionState) {
        if (isStopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }
}
