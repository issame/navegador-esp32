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

/** Convierte iconos de Android al formato 48x48 de 1 bit del firmware. */
object IconConverter {
    private const val S = IconBits.SIZE

    fun fromDrawable(d: Drawable): ByteArray? {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: S
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: S
        val scale = min(S.toFloat() / iw, S.toFloat() / ih)
        val w = (iw * scale).roundToInt().coerceIn(1, S)
        val h = (ih * scale).roundToInt().coerceIn(1, S)
        val left = (S - w) / 2
        val top = (S - h) / 2
        d.setBounds(left, top, left + w, top + h)
        d.draw(canvas)
        val px = IntArray(S * S)
        bmp.getPixels(px, 0, S, 0, 0, S, S)
        bmp.recycle()
        return IconBits.pack(px)
    }

    fun fromBitmap(ctx: Context, b: Bitmap): ByteArray? = fromDrawable(BitmapDrawable(ctx.resources, b))

    /** Vista previa ampliada sin suavizar, como se verá en la pantalla. */
    fun preview(bits: ByteArray, scale: Int = 4): Bitmap {
        val small = Bitmap.createBitmap(IconBits.unpack(bits), S, S, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, S * scale, S * scale, false)
    }

    /** Flecha de "gira a la izquierda" para la prueba de navegación. */
    fun testArrow(): ByteArray? {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(Path().apply {
            moveTo(30f, 45f)
            lineTo(30f, 26f)
            quadTo(30f, 20f, 24f, 20f)
            lineTo(16f, 20f)
        }, stroke)
        canvas.drawPath(Path().apply {
            moveTo(4f, 20f)
            lineTo(17f, 9f)
            lineTo(17f, 31f)
            close()
        }, fill)
        val px = IntArray(S * S)
        bmp.getPixels(px, 0, S, 0, 0, S, S)
        bmp.recycle()
        return IconBits.pack(px)
    }
}
