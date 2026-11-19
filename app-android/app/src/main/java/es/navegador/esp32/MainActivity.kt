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

/**
 * Pantalla principal: estado de la conexión con la placa, navegación actual,
 * permisos y acceso al registro.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val SCAN_DURATION_MS = 15_000L
    }

    private lateinit var binding: ActivityMainBinding

    /** Si el usuario pulsó "Buscar pantalla" sin permisos, se busca al concederlos. */
    private var scanWhenPermissionGranted = false

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
            if (Perms.hasBluetooth(this)) {
                if (scanWhenPermissionGranted) startScan()
                startServiceIfPaired()
            }
            scanWhenPermissionGranted = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpButtons()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes los permisos pueden haber cambiado.
        refreshPermissions()
        refreshPairedDevice()
        startServiceIfPaired()
    }

    private fun setUpButtons() {
        binding.btnScan.setOnClickListener {
            if (Perms.hasBluetooth(this)) {
                startScan()
            } else {
                scanWhenPermissionGranted = true
                permissionRequest.launch(Perms.runtimePermissions())
            }
        }
        binding.btnForget.setOnClickListener {
            BleService.stop(this)
            Prefs.forgetDevice(this)
            Hub.log("Pantalla olvidada")
            showToast(getString(R.string.pantalla_olvidada))
            refreshPairedDevice()
        }
        binding.btnTime.setOnClickListener {
            if (Hub.connection.value == ConnectionState.CONNECTED) Hub.request(Hub.Request.SEND_TIME)
            else showToast(getString(R.string.no_conectada))
        }
        binding.btnTest.setOnClickListener { toggleTestNavigation() }
        binding.btnPermBle.setOnClickListener { permissionRequest.launch(Perms.runtimePermissions()) }
        binding.btnPermNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
    }

    /** Actualiza la pantalla cada vez que cambia algo en [Hub], mientras está visible. */
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Hub.connection.collect(::showConnection) }
                launch { Hub.nav.collect(::showNavigation) }
                launch {
                    Hub.lastTimeSent.collect { time ->
                        binding.txtTime.text = if (time == null) getString(R.string.hora_no_enviada)
                        else getString(R.string.hora_enviada, time)
                    }
                }
                launch {
                    Hub.testActive.collect { active ->
                        binding.btnTest.setText(if (active) R.string.detener_prueba else R.string.probar_navegacion)
                    }
                }
            }
        }
    }

    private fun startServiceIfPaired() {
        if (Prefs.deviceAddress(this) != null && Perms.hasBluetooth(this) &&
            Hub.connection.value == ConnectionState.DISCONNECTED
        ) {
            BleService.start(this)
        }
    }

    // ---------------------------------------------------------------- mostrar estado

    private fun refreshPairedDevice() {
        val address = Prefs.deviceAddress(this)
        binding.txtDevice.text = if (address == null) getString(R.string.sin_pantalla)
        else "${Prefs.deviceName(this) ?: getString(R.string.sin_nombre)}  ·  $address"
        binding.btnForget.visibility = if (address == null) View.GONE else View.VISIBLE
    }

    private fun showConnection(state: ConnectionState) {
        binding.txtState.text = state.label
        val colorRes = when (state) {
            ConnectionState.CONNECTED -> R.color.verde
            ConnectionState.DISCONNECTED -> R.color.gris
            else -> R.color.ambar
        }
        binding.txtState.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showNavigation(nav: NavInfo) {
        if (!nav.active) {
            binding.txtManeuver.text = getString(R.string.sin_navegacion)
            binding.txtInstruction.text = getString(R.string.inicia_ruta)
            binding.txtSummary.text = ""
            binding.imgIcon.setImageResource(R.drawable.ic_stat_map)
            return
        }
        binding.txtManeuver.text = nav.maneuverDistance
        binding.txtInstruction.text = nav.instruction
        binding.txtSummary.text = nav.summary
        val icon = nav.icon
        if (icon != null) binding.imgIcon.setImageBitmap(IconConverter.preview(icon))
        else binding.imgIcon.setImageResource(R.drawable.ic_stat_map)
    }

    private fun refreshPermissions() {
        val hasBluetooth = Perms.hasBluetooth(this)
        binding.txtPermBle.setText(if (hasBluetooth) R.string.perm_bluetooth_ok else R.string.perm_bluetooth_falta)
        binding.btnPermBle.visibility = if (hasBluetooth) View.GONE else View.VISIBLE

        val hasNotificationAccess = Perms.hasNotificationAccess(this)
        binding.txtPermNotif.setText(if (hasNotificationAccess) R.string.perm_notif_ok else R.string.perm_notif_falta)
        binding.btnPermNotif.visibility = if (hasNotificationAccess) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- ruta de prueba

    /** Envía (o retira) una ruta inventada con acentos para comprobar la pantalla sin salir. */
    private fun toggleTestNavigation() {
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

    // ---------------------------------------------------------------- búsqueda de la placa

    /**
     * Busca durante 15 s placas que anuncien el servicio del firmware y las muestra en
     * un diálogo. Al elegir una se guarda y se (re)arranca [BleService].
     */
    @SuppressLint("MissingPermission") // se comprueba antes de llamar
    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            showToast(getString(R.string.sin_bluetooth))
            return
        }
        if (!adapter.isEnabled) {
            showToast(getString(R.string.bluetooth_apagado))
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return

        // Placas encontradas, en el mismo orden que la lista del diálogo.
        val foundAddresses = mutableListOf<String>()
        val namesByAddress = mutableMapOf<String, String>()
        val listItems = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name ?: getString(R.string.sin_nombre)
                val label = "$name\n$address  ·  ${result.rssi} dBm"
                runOnUiThread {
                    val index = foundAddresses.indexOf(address)
                    if (index < 0) {
                        foundAddresses += address
                        namesByAddress[address] = name
                        listItems.add(label)
                    } else {
                        // Ya estaba: se actualiza la intensidad de señal.
                        listItems.getItem(index)?.let(listItems::remove)
                        listItems.insert(label, index)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { showToast(getString(R.string.error_busqueda, errorCode)) }
            }
        }

        val onlyOurBoards = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleService.SERVICE_UUID)).build()
        )
        val fastScan = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(onlyOurBoards, fastScan, scanCallback)

        val handler = Handler(Looper.getMainLooper())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.buscando)
            .setAdapter(listItems) { _, position ->
                val address = foundAddresses[position]
                Prefs.saveDevice(this, address, namesByAddress[address])
                Hub.log("Pantalla elegida: ${namesByAddress[address]} ($address)")
                refreshPairedDevice()
                BleService.stop(this) // por si estaba conectado a otra placa
                BleService.start(this)
            }
            .setNegativeButton(R.string.cancelar, null)
            .setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(scanCallback) }
            }
            .show()

        handler.postDelayed({
            runCatching { scanner.stopScan(scanCallback) }
            dialog.setTitle(if (foundAddresses.isEmpty()) R.string.no_encontrada else R.string.elige_pantalla)
        }, SCAN_DURATION_MS)
    }

    private fun showToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
