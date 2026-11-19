package es.navegador.esp32

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Convierte iconos de Android (la flecha de la notificación de Maps) al formato de
 * 48x48 a 1 bit que usa la placa. El empaquetado de bits lo hace [IconBits].
 */
object IconConverter {
    private const val SIZE = IconBits.SIZE

    /** Dibuja [drawable] centrado y sin deformar en 48x48 y lo empaqueta. */
    fun fromDrawable(drawable: Drawable): ByteArray? {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Escala para que quepa entero manteniendo la proporción.
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val scale = min(SIZE.toFloat() / sourceWidth, SIZE.toFloat() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, SIZE)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, SIZE)
        val left = (SIZE - width) / 2
        val top = (SIZE - height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        drawable.draw(canvas)

        return packAndRecycle(bitmap)
    }

    fun fromBitmap(context: Context, bitmap: Bitmap): ByteArray? =
        fromDrawable(BitmapDrawable(context.resources, bitmap))

    /** Vista previa ampliada sin suavizar, tal como se verá en la pantalla redonda. */
    fun preview(packed: ByteArray, zoom: Int = 4): Bitmap {
        val small = Bitmap.createBitmap(IconBits.unpack(packed), SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, SIZE * zoom, SIZE * zoom, false)
    }

    /** Flecha de "gira a la izquierda" que usa el botón "Probar navegación". */
    fun testArrow(): ByteArray? {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        // Tramo vertical que gira hacia la izquierda…
        canvas.drawPath(Path().apply {
            moveTo(30f, 45f)
            lineTo(30f, 26f)
            quadTo(30f, 20f, 24f, 20f)
            lineTo(16f, 20f)
        }, linePaint)
        // …y punta de la flecha.
        canvas.drawPath(Path().apply {
            moveTo(4f, 20f)
            lineTo(17f, 9f)
            lineTo(17f, 31f)
            close()
        }, headPaint)

        return packAndRecycle(bitmap)
    }

    private fun packAndRecycle(bitmap: Bitmap): ByteArray? {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()
        return IconBits.pack(pixels)
    }
}
