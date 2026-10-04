package com.example.andvibe

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.io.File

object FileIcons {
    private data class Glyph(val label: String, val color: Int)

    private const val FOLDER = 0xFFDCB67A.toInt()
    private const val PLAIN = 0xFF8B949E.toInt()

    private val byName = mapOf(
        "build.gradle" to Glyph("G", 0xFF5FB4C8.toInt()),
        "build.gradle.kts" to Glyph("G", 0xFF5FB4C8.toInt()),
        "settings.gradle" to Glyph("G", 0xFF5FB4C8.toInt()),
        "settings.gradle.kts" to Glyph("G", 0xFF5FB4C8.toInt()),
        "gradlew" to Glyph(">_", 0xFF3FB950.toInt()),
        "dockerfile" to Glyph("D", 0xFF2496ED.toInt()),
        "makefile" to Glyph("M", 0xFFE37933.toInt()),
        "license" to Glyph("©", 0xFFD29922.toInt()),
        "package.json" to Glyph("{}", 0xFF3FB950.toInt()),
        ".gitignore" to Glyph("◆", 0xFFF05033.toInt()),
        ".gitattributes" to Glyph("◆", 0xFFF05033.toInt()),
        "androidmanifest.xml" to Glyph("A", 0xFF3DDC84.toInt()),
    )

    private val byExt = mapOf(
        "kt" to Glyph("K", 0xFFA97BFF.toInt()),
        "kts" to Glyph("K", 0xFFA97BFF.toInt()),
        "java" to Glyph("J", 0xFFE76F00.toInt()),
        "js" to Glyph("JS", 0xFFF1E05A.toInt()),
        "mjs" to Glyph("JS", 0xFFF1E05A.toInt()),
        "cjs" to Glyph("JS", 0xFFF1E05A.toInt()),
        "jsx" to Glyph("⚛", 0xFF61DAFB.toInt()),
        "ts" to Glyph("TS", 0xFF3178C6.toInt()),
        "tsx" to Glyph("⚛", 0xFF3178C6.toInt()),
        "json" to Glyph("{}", 0xFFCBCB41.toInt()),
        "xml" to Glyph("<>", 0xFFE37933.toInt()),
        "html" to Glyph("<>", 0xFFE34C26.toInt()),
        "htm" to Glyph("<>", 0xFFE34C26.toInt()),
        "svg" to Glyph("<>", 0xFFFFB13B.toInt()),
        "css" to Glyph("#", 0xFF519ABA.toInt()),
        "scss" to Glyph("#", 0xFFF55385.toInt()),
        "md" to Glyph("M↓", 0xFF519ABA.toInt()),
        "txt" to Glyph("≡", PLAIN),
        "py" to Glyph("py", 0xFF3572A5.toInt()),
        "rs" to Glyph("R", 0xFFDEA584.toInt()),
        "go" to Glyph("go", 0xFF00ADD8.toInt()),
        "c" to Glyph("C", 0xFF599EFF.toInt()),
        "h" to Glyph("h", 0xFFA074C4.toInt()),
        "cpp" to Glyph("C+", 0xFFF34B7D.toInt()),
        "swift" to Glyph("S", 0xFFF05138.toInt()),
        "sh" to Glyph(">_", 0xFF3FB950.toInt()),
        "bat" to Glyph(">_", 0xFF3FB950.toInt()),
        "yml" to Glyph("Y", 0xFFCB171E.toInt()),
        "yaml" to Glyph("Y", 0xFFCB171E.toInt()),
        "toml" to Glyph("T", 0xFF9C4221.toInt()),
        "properties" to Glyph("⚙", PLAIN),
        "pro" to Glyph("⚙", PLAIN),
        "gradle" to Glyph("G", 0xFF5FB4C8.toInt()),
        "png" to Glyph("▣", 0xFFA074C4.toInt()),
        "jpg" to Glyph("▣", 0xFFA074C4.toInt()),
        "jpeg" to Glyph("▣", 0xFFA074C4.toInt()),
        "webp" to Glyph("▣", 0xFFA074C4.toInt()),
        "gif" to Glyph("▣", 0xFFA074C4.toInt()),
        "ico" to Glyph("▣", 0xFFA074C4.toInt()),
        "apk" to Glyph("A", 0xFF3DDC84.toInt()),
        "jar" to Glyph("J", 0xFFE76F00.toInt()),
        "zip" to Glyph("▤", 0xFFD29922.toInt()),
        "lock" to Glyph("≡", PLAIN),
    )

    fun forFile(file: File, sizePx: Int): Drawable {
        if (file.isDirectory) return FolderDrawable(sizePx)
        val name = file.name.lowercase()
        val glyph = byName[name] ?: byExt[name.substringAfterLast('.', "")] ?: Glyph("•", PLAIN)
        return GlyphDrawable(glyph.label, glyph.color, sizePx)
    }

    private class GlyphDrawable(
        private val label: String,
        color: Int,
        private val size: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * if (label.length > 1) 0.62f else 0.85f
        }

        init {
            setBounds(0, 0, size, size)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(label, b.exactCenterX(), y, paint)
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class FolderDrawable(private val size: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FOLDER }
        private val path = Path()

        init {
            setBounds(0, 0, size, size)
        }

        override fun draw(canvas: Canvas) {
            val s = bounds.width().toFloat()
            val l = bounds.left + s * 0.06f
            val r = bounds.left + s * 0.94f
            val t = bounds.top + s * 0.2f
            val bottom = bounds.top + s * 0.82f
            val tabR = l + s * 0.38f
            val bodyT = t + s * 0.12f
            val rad = s * 0.08f
            path.reset()
            path.addRoundRect(RectF(l, t, tabR, bodyT + rad), rad, rad, Path.Direction.CW)
            path.addRoundRect(RectF(l, bodyT, r, bottom), rad, rad, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
