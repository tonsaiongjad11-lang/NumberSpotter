package com.tonsai.thaihighlight

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Purely visual comparison. Thai text is NOT passed through Latin-only OCR.
 * Foreground shape from the orange target is normalized and compared with
 * freshly extracted shapes from all 25 cells on EVERY captured frame.
 * No ordering guess, saved locations, or automatic touch input.
 */
class ShapeMatcher {
    private companion object { const val SIZE = 40 }

    data class Result(
        val row: Int, val col: Int, val rect: Rect,
        val score: Float, val secondScore: Float
    )
    data class Reading(val match: Result?, val message: String)
    private data class Glyph(val bits: BooleanArray, val count: Int, val aspect: Float)
    private data class Component(val pixels: IntArray, val length: Int, val area: Int,
                                 val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun scan(bitmap: Bitmap, actualWidth: Int, actualHeight: Int, config: RegionConfig): Reading {
        val board = config.board.toPixels(actualWidth, actualHeight)
        val cueRect = config.cue.toPixels(actualWidth, actualHeight)
        if (board.width() < 30 || board.height() < 30) return Reading(null, "กรอบตารางเล็กเกินไป")
        val cue = extract(bitmap, cueRect, preferOrange = true)
            ?: return Reading(null, "ไม่พบตัวสีส้ม: ปรับกรอบม่วงให้พอดีกับอักษรเป้าหมาย")

        var bestScore = -1f
        var nextScore = -1f
        var bestRow = -1
        var bestCol = -1
        var bestRect: Rect? = null
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                val rect = cellRect(board, row, col)
                // Stay clear of colored grid borders and of the thin overlay outline.
                val inner = insetByFraction(rect, 0.15f)
                val item = extract(bitmap, inner, preferOrange = false) ?: continue
                val s = similarity(cue, item)
                if (s > bestScore) {
                    nextScore = bestScore
                    bestScore = s
                    bestRow = row
                    bestCol = col
                    bestRect = rect
                } else if (s > nextScore) {
                    nextScore = s
                }
            }
        }
        if (bestRect == null) return Reading(null, "ไม่พบรูปทรงอักษรใน 25 ช่อง: ตรวจกรอบตาราง")
        val confidenceOk = bestScore >= config.cutoff
        val separationOk = bestScore - nextScore >= config.minGap
        if (!confidenceOk || !separationOk) {
            return Reading(null, "ยังไม่แน่ใจ (ใกล้สุด ${(bestScore * 100).roundToInt()}%, รอง ${(nextScore * 100).roundToInt()}%) จึงไม่วงเพื่อเลี่ยงผิดช่อง")
        }
        return Reading(
            Result(bestRow, bestCol, bestRect, bestScore, nextScore),
            "พบช่องแถว ${bestRow + 1} คอลัมน์ ${bestCol + 1} • ตรงกัน ${(bestScore * 100).roundToInt()}%"
        )
    }

    fun cellRect(board: Rect, row: Int, col: Int) = Rect(
        board.left + (board.width().toFloat() * col / 5).roundToInt(),
        board.top + (board.height().toFloat() * row / 5).roundToInt(),
        board.left + (board.width().toFloat() * (col + 1) / 5).roundToInt(),
        board.top + (board.height().toFloat() * (row + 1) / 5).roundToInt()
    )

    private fun insetByFraction(rect: Rect, f: Float): Rect {
        val px = (rect.width() * f).roundToInt()
        val py = (rect.height() * f).roundToInt()
        return Rect(rect.left + px, rect.top + py, rect.right - px, rect.bottom - py)
    }

    private fun extract(bitmap: Bitmap, roi: Rect, preferOrange: Boolean): Glyph? {
        if (roi.width() < 6 || roi.height() < 6 ||
            roi.left < 0 || roi.top < 0 || roi.right > bitmap.width || roi.bottom > bitmap.height) return null

        val width = roi.width()
        val height = roi.height()
        val length = width * height
        val argb = IntArray(length)
        bitmap.getPixels(argb, 0, width, roi.left, roi.top, width, height)
        val light = IntArray(length)
        val histogram = IntArray(256)
        val orange = BooleanArray(length)
        var countOrange = 0
        for (i in argb.indices) {
            val c = argb[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val y = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
            light[i] = y
            histogram[y]++
            // Wide range for orange/yellow-orange glyphs; excludes blue/green borders.
            val isOrange = r >= 125 && r > b * 1.43f && g > b * 1.1f && r > g * 1.08f
            orange[i] = isOrange
            if (isOrange) countOrange++
        }
        val foreground = BooleanArray(length)
        val useOrange = preferOrange && countOrange >= max(8, length / 1800) && countOrange <= length * 0.37f
        if (useOrange) {
            orange.copyInto(foreground)
        } else {
            // Otsu finds the main glyph/background separation, independent of text color.
            val t = otsu(histogram, length)
            var borderLight = 0L
            var borderCount = 0
            val strip = max(1, min(width, height) / 12)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x < strip || y < strip || x >= width - strip || y >= height - strip) {
                        borderLight += light[y * width + x]
                        borderCount++
                    }
                }
            }
            val backgroundIsLight = borderCount == 0 || borderLight.toFloat() / borderCount >= t
            for (i in light.indices) {
                foreground[i] = if (backgroundIsLight) light[i] <= t else light[i] > t
            }
        }

        // Drop large solid blocks (e.g. orange title backgrounds) rather than fake a match.
        val rawCount = foreground.count { it }
        if (rawCount < 5 || rawCount > length * 0.65f) return null
        val components = components(foreground, width, height)
        if (components.isEmpty()) return null
        val ranked = components.sortedByDescending { it.area }
        // Prefer central glyphs over decorative noise, while allowing slight off-center crop.
        val lead = ranked.firstOrNull {
            it.area >= 4 && midpointInSafeArea(it, width, height)
        } ?: ranked.first()
        if (lead.area < 4 || lead.area > length * 0.57f) return null

        val selected = BooleanArray(length)
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        val leadCenterX = (lead.left + lead.right) * 0.5f
        val leadCenterY = (lead.top + lead.bottom) * 0.5f
        for (c in components) {
            val cx = (c.left + c.right) * 0.5f
            val cy = (c.top + c.bottom) * 0.5f
            val near = abs(cx - leadCenterX) < width * 0.32f && abs(cy - leadCenterY) < height * 0.32f
            val keep = c === lead || (c.area >= max(2, lead.area / 90) && near && c.area < lead.area * 1.5f)
            if (!keep) continue
            for (i in 0 until c.length) selected[c.pixels[i]] = true
            x0 = min(x0, c.left); y0 = min(y0, c.top)
            x1 = max(x1, c.right); y1 = max(y1, c.bottom)
        }
        if (x1 <= x0 || y1 <= y0) return null
        val actualGlyphWidth = x1 - x0 + 1
        val actualGlyphHeight = y1 - y0 + 1
        val aspect = actualGlyphWidth.toFloat() / actualGlyphHeight
        if (aspect < 0.17f || aspect > 5.8f) return null

        val scale = 34f / max(actualGlyphWidth, actualGlyphHeight)
        val scaledW = actualGlyphWidth * scale
        val scaledH = actualGlyphHeight * scale
        val offsetX = (SIZE - scaledW) / 2f
        val offsetY = (SIZE - scaledH) / 2f
        val bits = BooleanArray(SIZE * SIZE)
        var pixels = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val sx = floor((x + 0.5f - offsetX) / scale + x0).toInt()
                val sy = floor((y + 0.5f - offsetY) / scale + y0).toInt()
                val hit = sx in 0 until width && sy in 0 until height && selected[sy * width + sx]
                bits[y * SIZE + x] = hit
                if (hit) pixels++
            }
        }
        if (pixels < 15 || pixels > SIZE * SIZE * 0.7f) return null
        return Glyph(bits, pixels, aspect)
    }

    private fun midpointInSafeArea(c: Component, w: Int, h: Int): Boolean {
        val x = (c.left + c.right) / 2f
        val y = (c.top + c.bottom) / 2f
        return x in (w * 0.10f)..(w * 0.90f) && y in (h * 0.10f)..(h * 0.90f)
    }

    private fun components(mask: BooleanArray, w: Int, h: Int): List<Component> {
        val seen = BooleanArray(w * h)
        val q = IntArray(w * h)
        val found = ArrayList<Component>()
        for (at in mask.indices) {
            if (!mask[at] || seen[at]) continue
            var head = 0
            var tail = 1
            q[0] = at
            seen[at] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (head < tail) {
                val cur = q[head++]
                val x = cur % w; val y = cur / w
                minX = min(minX, x); minY = min(minY, y)
                maxX = max(maxX, x); maxY = max(maxY, y)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val pos = ny * w + nx
                        if (mask[pos] && !seen[pos]) {
                            seen[pos] = true
                            q[tail++] = pos
                        }
                    }
                }
            }
            if (tail >= 2) found.add(Component(q.copyOf(tail), tail, tail, minX, minY, maxX, maxY))
        }
        return found
    }

    private fun otsu(hist: IntArray, count: Int): Int {
        var sumAll = 0.0
        for (i in hist.indices) sumAll += i.toDouble() * hist[i]
        var leftSum = 0.0
        var leftCount = 0
        var bestThreshold = 127
        var bestVariance = -1.0
        for (t in 0 until 256) {
            leftCount += hist[t]
            if (leftCount <= 0) continue
            val rightCount = count - leftCount
            if (rightCount <= 0) break
            leftSum += t.toDouble() * hist[t]
            val m0 = leftSum / leftCount
            val m1 = (sumAll - leftSum) / rightCount
            val variance = leftCount.toDouble() * rightCount * (m0 - m1) * (m0 - m1)
            if (variance > bestVariance) { bestVariance = variance; bestThreshold = t }
        }
        return bestThreshold
    }

    private fun similarity(a: Glyph, b: Glyph): Float {
        val aMask = a.bits
        val bMask = b.bits
        var exact = 0
        var aClose = 0
        var bClose = 0
        for (i in aMask.indices) {
            if (aMask[i] && bMask[i]) exact++
            if (aMask[i] && withinOnePixel(bMask, i)) aClose++
            if (bMask[i] && withinOnePixel(aMask, i)) bClose++
        }
        val denom = (a.count + b.count).toFloat()
        val strictDice = 2f * exact / denom
        val softDice = (aClose + bClose).toFloat() / denom
        val ratioPenalty = abs(kotlin.math.ln((a.aspect / b.aspect).toDouble())).toFloat() * 0.10f
        return (strictDice * 0.52f + softDice * 0.48f - ratioPenalty).coerceIn(0f, 1f)
    }

    private fun withinOnePixel(mask: BooleanArray, i: Int): Boolean {
        val x = i % SIZE; val y = i / SIZE
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until SIZE && ny in 0 until SIZE && mask[ny * SIZE + nx]) return true
        }
        return false
    }
}
