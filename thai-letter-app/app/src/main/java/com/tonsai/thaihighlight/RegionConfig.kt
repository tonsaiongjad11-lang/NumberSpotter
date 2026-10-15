package com.tonsai.thaihighlight

import android.content.Context
import android.graphics.Rect

/** All ROI coordinates are in full-screen percentages, not dp. */
data class PercentRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPixels(width: Int, height: Int): Rect = Rect(
        (width * left / 100f).toInt().coerceIn(0, width - 1),
        (height * top / 100f).toInt().coerceIn(0, height - 1),
        (width * right / 100f).toInt().coerceIn(1, width),
        (height * bottom / 100f).toInt().coerceIn(1, height)
    )
    fun valid(): Boolean = left >= 0 && top >= 0 && right <= 100 && bottom <= 100 &&
        right - left >= 4f && bottom - top >= 4f
}

data class RegionConfig(
    val board: PercentRegion = PercentRegion(4f, 28f, 96f, 86f),
    val cue: PercentRegion = PercentRegion(68f, 8f, 98f, 23f),
    val cutoff: Float = 0.61f,
    val minGap: Float = 0.028f,
    val showGuides: Boolean = true
) {
    companion object {
        fun read(context: Context): RegionConfig {
            val p = context.getSharedPreferences("regions", Context.MODE_PRIVATE)
            fun reg(prefix: String, default: PercentRegion) = PercentRegion(
                p.getFloat("${prefix}L", default.left),
                p.getFloat("${prefix}T", default.top),
                p.getFloat("${prefix}R", default.right),
                p.getFloat("${prefix}B", default.bottom)
            )
            return RegionConfig(
                board = reg("board", PercentRegion(4f, 28f, 96f, 86f)),
                cue = reg("cue", PercentRegion(68f, 8f, 98f, 23f)),
                cutoff = p.getFloat("cutoff", 0.61f),
                minGap = p.getFloat("minGap", 0.028f),
                showGuides = p.getBoolean("guides", true)
            )
        }
    }
    fun save(context: Context) {
        val p = context.getSharedPreferences("regions", Context.MODE_PRIVATE).edit()
        fun saveReg(prefix: String, r: PercentRegion) {
            p.putFloat("${prefix}L", r.left)
            p.putFloat("${prefix}T", r.top)
            p.putFloat("${prefix}R", r.right)
            p.putFloat("${prefix}B", r.bottom)
        }
        saveReg("board", board)
        saveReg("cue", cue)
        p.putFloat("cutoff", cutoff)
        p.putFloat("minGap", minGap)
        p.putBoolean("guides", showGuides)
        p.apply()
    }
}
