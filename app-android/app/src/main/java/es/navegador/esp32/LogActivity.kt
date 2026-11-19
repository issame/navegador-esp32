package es.navegador.esp32

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.navegador.esp32.databinding.ActivityLogBinding
import kotlinx.coroutines.launch

/**
 * Registro de lo que llega de la app de navegación (tal cual) y de lo que se envía a la placa.
 * Sirve para ajustar [NotificationParser] si una app cambia el formato.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClear.setOnClickListener { Hub.clearLog() }
        binding.btnShare.setOnClickListener { shareLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Hub.logLines.collect { lines ->
                    binding.txtLog.text = if (lines.isEmpty()) getString(R.string.registro_vacio)
                    else lines.joinToString("\n")
                    binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) } // ir al final
                }
            }
        }
    }

    /** Comparte el registro completo como texto (el archivo incluye sesiones anteriores). */
    private fun shareLog() {
        val logText = Hub.logFileIfExists()?.readText() ?: Hub.logLines.value.joinToString("\n")
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.registro))
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.compartir)))
    }
}
