package es.navegador.esp32

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.navegador.esp32.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var scanAfterPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
            if (Perms.hasBluetooth(this)) {
                if (scanAfterPermission) startScan()
                ensureServiceRunning()
            }
            scanAfterPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnScan.setOnClickListener {
            if (Perms.hasBluetooth(this)) {
                startScan()
            } else {
                scanAfterPermission = true
                permissionLauncher.launch(Perms.runtime())
            }
        }
        b.btnForget.setOnClickListener {
            BleService.stop(this)
            Prefs.clear(this)
            Hub.log("Pantalla olvidada")
            toast(getString(R.string.pantalla_olvidada))
            refreshDevice()
        }
        b.btnTime.setOnClickListener {
            if (Hub.conn.value == ConnState.CONECTADO) Hub.request(Hub.Request.SEND_TIME)
            else toast(getString(R.string.no_conectada))
        }
        b.btnTest.setOnClickListener { toggleTest() }
        b.btnPermBle.setOnClickListener { permissionLauncher.launch(Perms.runtime()) }
        b.btnPermNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        b.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Hub.conn.collect(::showConnection) }
                launch { Hub.nav.collect(::showNav) }
                launch {
                    Hub.lastTime.collect { t ->
                        b.txtTime.text = if (t == null) getString(R.string.hora_no_enviada)
                        else getString(R.string.hora_enviada, t)
                    }
                }
                launch {
                    Hub.testActive.collect { active ->
                        b.btnTest.setText(if (active) R.string.detener_prueba else R.string.probar_navegacion)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshDevice()
        ensureServiceRunning()
    }

    private fun ensureServiceRunning() {
        if (Prefs.deviceAddress(this) != null && Perms.hasBluetooth(this) &&
            Hub.conn.value == ConnState.DESCONECTADO
        ) {
            BleService.start(this)
        }
    }

    // ---------------------------------------------------------------- estado

    private fun refreshDevice() {
        val address = Prefs.deviceAddress(this)
        b.txtDevice.text = if (address == null) getString(R.string.sin_pantalla)
        else "${Prefs.deviceName(this) ?: getString(R.string.sin_nombre)}  ·  $address"
        b.btnForget.visibility = if (address == null) View.GONE else View.VISIBLE
    }

    private fun showConnection(state: ConnState) {
        b.txtState.text = state.label
        val color = when (state) {
            ConnState.CONECTADO -> R.color.verde
            ConnState.DESCONECTADO -> R.color.gris
            else -> R.color.ambar
        }
        b.txtState.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun showNav(nav: NavInfo) {
        if (!nav.active) {
            b.txtManeuver.text = getString(R.string.sin_navegacion)
            b.txtInstruction.text = getString(R.string.inicia_ruta)
            b.txtSummary.text = ""
            b.imgIcon.setImageResource(R.drawable.ic_stat_map)
            return
        }
        b.txtManeuver.text = nav.maneuverDistance
        b.txtInstruction.text = nav.instruction
        b.txtSummary.text = nav.summary
        val icon = nav.icon
        if (icon != null) b.imgIcon.setImageBitmap(IconConverter.preview(icon))
        else b.imgIcon.setImageResource(R.drawable.ic_stat_map)
    }

    private fun refreshPermissions() {
        val ble = Perms.hasBluetooth(this)
        b.txtPermBle.setText(if (ble) R.string.perm_bluetooth_ok else R.string.perm_bluetooth_falta)
        b.btnPermBle.visibility = if (ble) View.GONE else View.VISIBLE

        val notif = Perms.hasNotificationAccess(this)
        b.txtPermNotif.setText(if (notif) R.string.perm_notif_ok else R.string.perm_notif_falta)
        b.btnPermNotif.visibility = if (notif) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- prueba

    private fun toggleTest() {
        if (Hub.testActive.value) {
            Hub.setTestActive(false)
            Hub.setNav(NavInfo.INACTIVE)
            Hub.log("Prueba de navegación detenida")
        } else {
            Hub.setTestActive(true)
            Hub.setNav(
                NavInfo(
                    active = true,
                    maneuverDistance = "250 m",
                    instruction = "Gira a la izquierda hacia la Plaça de l'Àngel (Peña, ¿sí? ¡ç!)",
                    duration = "19 min",
                    distance = "8,4 km",
                    eta = "14:51",
                    icon = IconConverter.testArrow(),
                )
            )
            Hub.log("Prueba de navegación iniciada")
        }
    }

    // ---------------------------------------------------------------- búsqueda

    @SuppressLint("MissingPermission") // se comprueba antes de llamar
    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast(getString(R.string.sin_bluetooth))
            return
        }
        if (!adapter.isEnabled) {
            toast(getString(R.string.bluetooth_apagado))
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return

        val addresses = mutableListOf<String>()
        val names = mutableMapOf<String, String>()
        val items = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name ?: getString(R.string.sin_nombre)
                val label = "$name\n$address  ·  ${result.rssi} dBm"
                runOnUiThread {
                    val index = addresses.indexOf(address)
                    if (index < 0) {
                        addresses += address
                        names[address] = name
                        items.add(label)
                    } else {
                        items.getItem(index)?.let(items::remove)
                        items.insert(label, index)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { toast(getString(R.string.error_busqueda, errorCode)) }
            }
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleService.SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)

        val handler = Handler(Looper.getMainLooper())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.buscando)
            .setAdapter(items) { _, which ->
                val address = addresses[which]
                Prefs.saveDevice(this, address, names[address])
                Hub.log("Pantalla elegida: ${names[address]} ($address)")
                refreshDevice()
                BleService.stop(this)
                BleService.start(this)
            }
            .setNegativeButton(R.string.cancelar, null)
            .setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
            }
            .show()

        handler.postDelayed({
            runCatching { scanner.stopScan(callback) }
            dialog.setTitle(if (addresses.isEmpty()) R.string.no_encontrada else R.string.elige_pantalla)
        }, 15_000)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
