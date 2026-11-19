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
 * Escucha las notificaciones del móvil y, cuando una app de navegación compatible está
 * guiando una ruta, lee su notificación fija y publica los datos en [Hub.nav].
 * [BleService] se encarga de enviarlos a la placa.
 *
 * Android solo lo activa si el usuario concede "Acceso a notificaciones" en Ajustes.
 */
class NavigationListener : NotificationListenerService() {

    /** App de navegación compatible. */
    private data class NavigationApp(
        val name: String,
        /** Si la flecha de la maniobra va en el icono pequeño (en las demás es el logo de la app). */
        val arrowInSmallIcon: Boolean = false,
    )

    companion object {
        private val SUPPORTED_APPS = mapOf(
            "com.google.android.apps.maps" to NavigationApp("Maps"),
            "com.waze" to NavigationApp("Waze"),
            "net.osmand.plus" to NavigationApp("OsmAnd+", arrowInSmallIcon = true),
            "net.osmand" to NavigationApp("OsmAnd", arrowInSmallIcon = true),
        )
    }

    /** Clave de la notificación de navegación que se está mostrando, para saber cuándo desaparece. */
    private var activeNotificationKey: String? = null

    /** Último texto registrado, para no llenar el registro con repeticiones. */
    private var lastLoggedTexts: String? = null

    override fun onListenerConnected() {
        Hub.log("Acceso a notificaciones activo")
        // Si ya había una ruta en marcha al activar el servicio, se lee lo que haya.
        runCatching {
            activeNotifications?.filter { it.packageName in SUPPORTED_APPS }?.forEach(::readNavigationNotification)
        }
    }

    override fun onListenerDisconnected() {
        Hub.log("Acceso a notificaciones desconectado")
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.packageName !in SUPPORTED_APPS) return
        runCatching { readNavigationNotification(statusBarNotification) }
            .onFailure { Hub.log("Error leyendo la notificación: ${it.message}") }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.key != activeNotificationKey) return
        val appName = SUPPORTED_APPS[statusBarNotification.packageName]?.name ?: "?"
        activeNotificationKey = null
        lastLoggedTexts = null
        Hub.log("$appName: navegación terminada")
        Hub.setNav(NavInfo.INACTIVE)
    }

    private fun readNavigationNotification(statusBarNotification: StatusBarNotification) {
        val app = SUPPORTED_APPS[statusBarNotification.packageName] ?: return
        val notification = statusBarNotification.notification
        val isNavigationCategory = notification.category == Notification.CATEGORY_NAVIGATION
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!isNavigationCategory && !isOngoing) return // notificaciones normales de la app

        val extras = notification.extras
        var texts = NotificationParser.NotificationTexts(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            extra = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
        )
        var navigation = NotificationParser.parse(texts)

        // Si los textos estándar no traen nada útil, se leen las vistas personalizadas
        // con las que la app dibuja la notificación.
        var customViews: CustomViewContent? = null
        if (!navigation.active) {
            customViews = readCustomViews(this, notification)
            if (customViews != null && customViews.texts.isNotEmpty()) {
                texts = texts.copy(extra = texts.extra + customViews.texts)
                navigation = NotificationParser.parse(texts)
            }
        }

        val description = "${app.name}: ${texts.describe()}"
        if (description != lastLoggedTexts) {
            lastLoggedTexts = description
            Hub.log(description)
        }

        // Otras notificaciones fijas (p. ej. "Compartiendo ubicación" o "Waze está en
        // marcha") no traen distancia de maniobra: así se distinguen de la de navegación.
        val isNavigation = if (isNavigationCategory) navigation.active else navigation.maneuverDistance.isNotEmpty()
        if (!isNavigation) return

        activeNotificationKey = statusBarNotification.key
        val arrow = loadArrow(notification, app) ?: customViews?.image?.let(IconConverter::fromDrawable)
        Hub.setTestActive(false) // una ruta real sustituye a la de prueba
        Hub.setNav(navigation.copy(icon = arrow))
    }

    /** La flecha de la maniobra suele ser el icono grande; en OsmAnd, el pequeño. */
    @Suppress("DEPRECATION")
    private fun loadArrow(notification: Notification, app: NavigationApp): ByteArray? = runCatching {
        notification.getLargeIcon()?.loadDrawable(this)?.let(IconConverter::fromDrawable)
            ?: (notification.extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
                ?.let { IconConverter.fromBitmap(this, it) }
            ?: if (app.arrowInSmallIcon) {
                notification.smallIcon?.loadDrawable(this)?.let(IconConverter::fromDrawable)
            } else {
                null
            }
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
