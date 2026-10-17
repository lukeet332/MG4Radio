package uk.lukeet.mg4radio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Cover art for stations: the car's DAB logo centred on a tile, or a generated name tile. */
object StationArtwork {
    private const val SIZE = 512

    fun forStation(station: Station, dabLogo: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (station.key.hashCode() and 0x7fffffff) % 360f
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.65f, 0.25f))
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        if (dabLogo != null) {
            drawLogo(canvas, dabLogo)
        } else {
            drawName(canvas, station.name.ifBlank { station.bandLabel })
        }
        if (dabLogo != null || station.name.isNotBlank()) drawBand(canvas, station.bandLabel)
        return bitmap
    }

    private fun drawLogo(canvas: Canvas, logo: Bitmap) {
        val box = SIZE * 0.72f
        val scale = minOf(box / logo.width, box / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2 - SIZE * 0.04f
        canvas.drawBitmap(logo, null, android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawName(canvas: Canvas, name: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = if (name.length <= 8) 96f else if (name.length <= 16) 72f else 56f
        }
        val width = (SIZE * 0.84f).toInt()
        val layout = StaticLayout.Builder.obtain(name, 0, name.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        canvas.save()
        canvas.translate((SIZE - width) / 2f, (SIZE - layout.height) / 2f - SIZE * 0.04f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawBand(canvas: Canvas, band: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(band, SIZE / 2f, SIZE * 0.9f, paint)
    }
}
