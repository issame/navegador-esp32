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
 * Mantiene la conexión BLE con la pantalla y le envía la hora y la navegación.
 * Se reconecta sola si se pierde la conexión.
 */
@SuppressLint("MissingPermission") // se comprueba en connect()
class BleService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        private const val CHANNEL_ID = "conexion"
        private const val NOTIF_ID = 1
        private const val MTU_WANTED = 517
        private const val DEFAULT_MTU = 23
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BleService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_MTU
    private var ready = false
    private var stopping = false
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var lastIconCrc: Long? = null

    @Volatile
    private var writeAck: CompletableDeferred<Boolean>? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Hub.log("Cambio de hora en el móvil")
            sendTime()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        ContextCompat.registerReceiver(
            this,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { for (chunk in writeQueue) writeChunk(chunk) }
        scope.launch { Hub.nav.collect { sendNav(it) } }
        scope.launch { Hub.requests.collect { if (it == Hub.Request.SEND_TIME) sendTime() } }
        scope.launch { Hub.conn.collect { updateNotification(it) } }
        scope.launch {
            // La placa no tiene reloj con batería: se resincroniza cada hora.
            while (isActive) {
                delay(3_600_000)
                sendTime()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (gatt == null && reconnectJob?.isActive != true) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        runCatching { unregisterReceiver(timeReceiver) }
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        ready = false
        Hub.setConn(ConnState.DESCONECTADO)
        Hub.log("Conexión detenida")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- conexión

    private fun connect() {
        if (stopping) return
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
            Hub.setConn(ConnState.BUSCANDO)
            scheduleReconnect()
            return
        }
        // Primer intento directo; después se deja a Android esperar a que la placa aparezca.
        val waitForDevice = attempts > 0
        Hub.setConn(if (waitForDevice) ConnState.BUSCANDO else ConnState.CONECTANDO)
        Hub.log("Conectando con $address" + if (waitForDevice) " (en espera)" else "")
        gatt = adapter.getRemoteDevice(address)
            .connectGatt(this, waitForDevice, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (stopping) return
        attempts++
        val wait = minOf(30_000L, 2_000L * attempts)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun onLinkLost(g: BluetoothGatt) {
        ready = false
        rx = null
        while (writeQueue.tryReceive().isSuccess) { /* descarta lo pendiente */ }
        writeAck?.complete(false)
        g.close()
        if (gatt === g) gatt = null
        if (stopping) return
        Hub.setConn(ConnState.BUSCANDO)
        scheduleReconnect()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    attempts = 0
                    Hub.log("Conectado; ajustando tamaño de paquete")
                    if (!g.requestMtu(MTU_WANTED)) g.discoverServices()
                } else {
                    Hub.log("Desconectado (código $status)")
                    onLinkLost(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            scope.launch {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
                Hub.log("Tamaño de paquete: $mtu bytes")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val c = g.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || c == null) {
                    Hub.log("La placa no tiene el servicio esperado")
                    g.disconnect()
                    return@launch
                }
                rx = c
                ready = true
                lastIconCrc = null
                Hub.setConn(ConnState.CONECTADO)
                Hub.log("Pantalla lista")
                sendTime()
                sendNav(Hub.nav.value)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // ---------------------------------------------------------------- envío

    private fun sendTime() {
        if (!ready) return
        val now = LocalDateTime.now()
        enqueue(Protocol.timePacket(now))
        val text = now.format(TIME_FMT)
        Hub.setLastTime(text)
        Hub.log("→ hora $text")
    }

    private fun sendNav(nav: NavInfo) {
        if (!ready) return
        if (!nav.active) {
            enqueue(Protocol.navInactivePacket())
            lastIconCrc = null
            Hub.log("→ navegación terminada")
            return
        }
        val icon = nav.icon
        val crc = icon?.let(Protocol::iconCrc) ?: 0xFFFFFFFFL
        enqueue(Protocol.navPacket(nav, crc))
        Hub.log("→ navegación: ${nav.maneuverDistance} | ${nav.instruction.replace('\n', ' ')} | ${nav.summary}")
        if (icon != null && crc != lastIconCrc) {
            Protocol.iconPackets(icon, crc).forEach(::enqueue)
            lastIconCrc = crc
            Hub.log("→ flecha nueva")
        }
    }

    private fun enqueue(packet: ByteArray) {
        if (!ready) return
        Protocol.split(packet, mtu - 3).forEach { writeQueue.trySend(it) }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val g = gatt
        val c = rx
        if (!ready || g == null || c == null) return
        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        if (!startWrite(g, c, chunk)) {
            Hub.log("No se pudo enviar un paquete (${chunk.size} B)")
            return
        }
        if (withTimeoutOrNull(3_000) { ack.await() } != true) {
            Hub.log("La pantalla no confirmó un paquete")
        }
    }

    @Suppress("DEPRECATION")
    private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = data
            g.writeCharacteristic(c)
        }

    // ---------------------------------------------------------------- notificación

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.canal_conexion),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = Prefs.deviceName(this) ?: getString(R.string.pantalla)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_map)
            .setContentTitle(name)
            .setContentText(state.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification(Hub.conn.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(state: ConnState) {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(state))
    }
}
