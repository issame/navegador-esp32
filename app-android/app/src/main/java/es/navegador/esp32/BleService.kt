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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Servicio en primer plano que mantiene la conexión BLE con la placa y le envía:
 *  - la fecha y la hora al conectar, cada hora y cuando cambia la hora del móvil;
 *  - la navegación cada vez que cambia [Hub.nav].
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Trozos pendientes de escribir; BLE solo admite una escritura a la vez. */
    private val pendingWrites = Channel<ByteArray>(Channel.UNLIMITED)

    private var currentGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = DEFAULT_MTU

    /** true cuando la placa está conectada y lista para recibir. */
    private var isReady = false

    /** true mientras el servicio se está cerrando: no hay que reconectar. */
    private var isStopping = false

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

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
        scope.launch { for (chunk in pendingWrites) writeChunk(chunk) }
        scope.launch { Hub.nav.collect { sendNavigation(it) } }
        scope.launch { Hub.requests.collect { if (it == Hub.Request.SEND_TIME) sendTime() } }
        scope.launch { Hub.connection.collect { updateNotification(it) } }
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
                isReady = true
                lastSentIconCrc = null // la placa puede haberse reiniciado: reenviar la flecha
                Hub.setConnection(ConnectionState.CONNECTED)
                Hub.log("Pantalla lista")
                sendTime()
                sendNavigation(Hub.nav.value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeConfirmation?.complete(status == BluetoothGatt.GATT_SUCCESS)
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

    private fun sendNavigation(nav: NavInfo) {
        if (!isReady) return
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
        if (!isReady) return
        Protocol.split(packet, negotiatedMtu - 3).forEach { pendingWrites.trySend(it) }
    }

    /** Escribe un trozo y espera la confirmación antes de pasar al siguiente. */
    private suspend fun writeChunk(chunk: ByteArray) {
        val gatt = currentGatt
        val characteristic = rxCharacteristic
        if (!isReady || gatt == null || characteristic == null) return
        val confirmation = CompletableDeferred<Boolean>()
        writeConfirmation = confirmation
        if (!startWrite(gatt, characteristic, chunk)) {
            Hub.log("No se pudo enviar un paquete (${chunk.size} B)")
            return
        }
        if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { confirmation.await() } != true) {
            Hub.log("La pantalla no confirmó un paquete")
        }
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
