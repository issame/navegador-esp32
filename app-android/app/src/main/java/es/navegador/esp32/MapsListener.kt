package es.navegador.esp32

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Lee la notificación fija que Google Maps muestra mientras navega y publica
 * los datos en [Hub.nav]; el servicio BLE se encarga de enviarlos.
 */
class MapsListener : NotificationListenerService() {

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    private var navKey: String? = null
    private var lastRaw: String? = null

    override fun onListenerConnected() {
        Hub.log("Acceso a notificaciones activo")
        runCatching {
            activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach(::handle)
        }
    }

    override fun onListenerDisconnected() {
        Hub.log("Acceso a notificaciones desconectado")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE) runCatching { handle(sbn) }.onFailure {
            Hub.log("Error leyendo Maps: ${it.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE && sbn.key == navKey) {
            navKey = null
            lastRaw = null
            Hub.log("Maps: navegación terminada")
            Hub.setNav(NavInfo.INACTIVE)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        val isNavigation = n.category == Notification.CATEGORY_NAVIGATION
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!isNavigation && !ongoing) return

        val e = n.extras
        var fields = MapsParser.Fields(
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            infoText = e.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            extra = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
        )
        var parsed = MapsParser.parse(fields)

        // Si los textos estándar no traen nada, se leen las vistas personalizadas de la notificación.
        var remote: RemoteContent? = null
        if (!parsed.active) {
            remote = readRemoteViews(this, n)
            if (remote != null && remote.texts.isNotEmpty()) {
                fields = fields.copy(extra = fields.extra + remote.texts)
                parsed = MapsParser.parse(fields)
            }
        }

        val raw = fields.describe()
        if (raw != lastRaw) {
            lastRaw = raw
            Hub.log("Maps: $raw")
        }

        // Una notificación fija de otro tipo (p. ej. compartir ubicación) no trae distancia de maniobra.
        val accepted = if (isNavigation) parsed.active else parsed.maneuverDistance.isNotEmpty()
        if (!accepted) return

        navKey = sbn.key
        val icon = loadIcon(n) ?: remote?.drawable?.let(IconConverter::fromDrawable)
        Hub.setTestActive(false)
        Hub.setNav(parsed.copy(icon = icon))
    }

    @Suppress("DEPRECATION")
    private fun loadIcon(n: Notification): ByteArray? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.let(IconConverter::fromDrawable)
            ?: (n.extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { IconConverter.fromBitmap(this, it) }
    }.getOrNull()

    class RemoteContent(val texts: List<String>, val drawable: Drawable?)

    @Suppress("DEPRECATION")
    private fun readRemoteViews(ctx: Context, n: Notification): RemoteContent? {
        val views = n.bigContentView ?: n.contentView ?: return null
        return runCatching {
            val root = views.apply(ctx, FrameLayout(ctx))
            val texts = mutableListOf<String>()
            var image: Drawable? = null
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                when (v) {
                    is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    is ImageView -> if (image == null) image = v.drawable
                    is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
            RemoteContent(texts, image)
        }.getOrNull()
    }
}
