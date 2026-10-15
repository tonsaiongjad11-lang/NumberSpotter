package com.tonsai.thaihighlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class HighlightOverlay(context: Context) : View(context) {
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 217, 103)
        strokeWidth = dp(5f)
        style = Paint.Style.STROKE
        setShadowLayer(dp(5f), 0f, 0f, Color.argb(160, 0, 120, 40))
    }
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 25, 153, 255)
        strokeWidth = dp(2.2f)
        style = Paint.Style.STROKE
    }
    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 180, 60, 220)
        strokeWidth = dp(2.2f)
        style = Paint.Style.STROKE
    }
    private var selected: Rect? = null
    private var board: Rect? = null
    private var cue: Rect? = null
    private var showingGuides = false

    fun update(found: Rect?, fullBoard: Rect, target: Rect, guides: Boolean) {
        selected = found?.let { Rect(it) }
        board = Rect(fullBoard)
        cue = Rect(target)
        showingGuides = guides
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showingGuides) {
            board?.let { canvas.drawRoundRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(), dp(5f),dp(5f),boardPaint) }
            cue?.let { canvas.drawRoundRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(), dp(5f),dp(5f),cuePaint) }
        }
        selected?.let {
            val pad = dp(3f)
            canvas.drawRoundRect(it.left+pad, it.top+pad, it.right-pad, it.bottom-pad,dp(12f),dp(12f),marker)
        }
    }
    private fun dp(n: Float) = n * resources.displayMetrics.density
}
