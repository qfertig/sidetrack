package com.sidetrack.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Two-pass box blur at full resolution using a sliding window (O(w*h)
 * regardless of radius). Cached by Coil so only computed once per image.
 */
class BitmapBlurTransformation(
    private val radius: Int = 5,
) : Transformation {
    override val cacheKey = "blur_$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        // Two passes of box blur approximates a Gaussian blur
        boxBlurPass(pixels, w, h)
        boxBlurPass(pixels, w, h)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int) {
        val tmp = IntArray(pixels.size)
        val div = radius * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = pixels[row + i.coerceIn(0, w - 1)]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            tmp[row] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            for (x in 1 until w) {
                val addP = pixels[row + (x + radius).coerceAtMost(w - 1)]
                r += (addP shr 16) and 0xFF; g += (addP shr 8) and 0xFF; b += addP and 0xFF
                val remP = pixels[row + (x - radius - 1).coerceAtLeast(0)]
                r -= (remP shr 16) and 0xFF; g -= (remP shr 8) and 0xFF; b -= remP and 0xFF
                tmp[row + x] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }

        // Vertical pass — write back into pixels
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            pixels[x] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            for (y in 1 until h) {
                val addP = tmp[(y + radius).coerceAtMost(h - 1) * w + x]
                r += (addP shr 16) and 0xFF; g += (addP shr 8) and 0xFF; b += addP and 0xFF
                val remP = tmp[(y - radius - 1).coerceAtLeast(0) * w + x]
                r -= (remP shr 16) and 0xFF; g -= (remP shr 8) and 0xFF; b -= remP and 0xFF
                pixels[y * w + x] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }
    }
}
