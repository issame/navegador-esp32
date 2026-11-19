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
 * Escucha las notificaciones del móvil y, cuando Google Maps está navegando, lee su
 * notificación fija y publica los datos en [Hub.nav]. [BleService] se encarga de
 * enviarlos a la placa.
 *
 * Android solo lo activa si el usuario concede "Acceso a notificaciones" en Ajustes.
 */
class MapsListener : NotificationListenerService() {

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    /** Clave de la notificación de navegación actual, para saber cuándo desaparece. */
    private var navigationNotificationKey: String? = null

    /** Último texto registrado, para no llenar el registro con repeticiones. */
    private var lastLoggedTexts: String? = null

    override fun onListenerConnected() {
        Hub.log("Acceso a notificaciones activo")
        // Si Maps ya estaba navegando al activar el servicio, se lee lo que haya.
        runCatching {
            activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach(::readMapsNotification)
        }
    }

    override fun onListenerDisconnected() {
        Hub.log("Acceso a notificaciones desconectado")
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.packageName != MAPS_PACKAGE) return
        runCatching { readMapsNotification(statusBarNotification) }
            .onFailure { Hub.log("Error leyendo Maps: ${it.message}") }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.packageName == MAPS_PACKAGE &&
            statusBarNotification.key == navigationNotificationKey
        ) {
            navigationNotificationKey = null
            lastLoggedTexts = null
            Hub.log("Maps: navegación terminada")
            Hub.setNav(NavInfo.INACTIVE)
        }
    }

    private fun readMapsNotification(statusBarNotification: StatusBarNotification) {
        val notification = statusBarNotification.notification
        val isNavigationCategory = notification.category == Notification.CATEGORY_NAVIGATION
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!isNavigationCategory && !isOngoing) return // notificaciones normales de Maps

        val extras = notification.extras
        var texts = MapsParser.NotificationTexts(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            extra = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
        )
        var navigation = MapsParser.parse(texts)

        // Si los textos estándar no traen nada útil, se leen las vistas personalizadas
        // con las que Maps dibuja la notificación.
        var customViews: CustomViewContent? = null
        if (!navigation.active) {
            customViews = readCustomViews(this, notification)
            if (customViews != null && customViews.texts.isNotEmpty()) {
                texts = texts.copy(extra = texts.extra + customViews.texts)
                navigation = MapsParser.parse(texts)
            }
        }

        val description = texts.describe()
        if (description != lastLoggedTexts) {
            lastLoggedTexts = description
            Hub.log("Maps: $description")
        }

        // Otra notificación fija de Maps (p. ej. "Compartiendo ubicación") no trae
        // distancia de maniobra: así se distingue de la de navegación.
        val isNavigation = if (isNavigationCategory) navigation.active else navigation.maneuverDistance.isNotEmpty()
        if (!isNavigation) return

        navigationNotificationKey = statusBarNotification.key
        val arrow = loadArrow(notification) ?: customViews?.image?.let(IconConverter::fromDrawable)
        Hub.setTestActive(false) // una ruta real sustituye a la de prueba
        Hub.setNav(navigation.copy(icon = arrow))
    }

    /** La flecha de la maniobra es el icono grande de la notificación. */
    @Suppress("DEPRECATION")
    private fun loadArrow(notification: Notification): ByteArray? = runCatching {
        notification.getLargeIcon()?.loadDrawable(this)?.let(IconConverter::fromDrawable)
            ?: (notification.extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
                ?.let { IconConverter.fromBitmap(this, it) }
    }.getOrNull()

    /** Textos e imagen encontrados en las vistas personalizadas de una notificación. */
    class CustomViewContent(val texts: List<String>, val image: Drawable?)

    /** Dibuja las vistas personalizadas de la notificación y recoge sus textos y su primera imagen. */
    @Suppress("DEPRECATION")
    private fun readCustomViews(context: Context, notification: Notification): CustomViewContent? {
        val remoteViews = notification.bigContentView ?: notification.contentView ?: return null
        return runCatching {
            val root = remoteViews.apply(context, FrameLayout(context))
            val texts = mutableListOf<String>()
            var firstImage: Drawable? = null
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                when (view) {
                    is TextView -> view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    is ImageView -> if (firstImage == null) firstImage = view.drawable
                    is ViewGroup -> for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
            visit(root)
            CustomViewContent(texts, firstImage)
        }.getOrNull()
    }
}
