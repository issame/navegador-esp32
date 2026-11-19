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

/** Registro de lo recibido de Maps y lo enviado a la pantalla, para poder ajustar la lectura. */
class LogActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnClear.setOnClickListener { Hub.clearLog() }
        b.btnShare.setOnClickListener {
            val text = Hub.logFileOrNull()?.readText() ?: Hub.lines.value.joinToString("\n")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.registro))
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.compartir)))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Hub.lines.collect { lines ->
                    b.txtLog.text = if (lines.isEmpty()) getString(R.string.registro_vacio)
                    else lines.joinToString("\n")
                    b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
