package com.example.trashrobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.abs

data class ObjectVisualProfile(
    val dominantColorHex: Int,
    val avgHue: Float,
    val avgSaturation: Float,
    val avgValue: Float,
    val aspectRatio: Float,
    val initialArea: Float,
    val colorName: String
)

data class Detection(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float,
    val visualProfile: ObjectVisualProfile? = null
)

class ObjectDetector(private val context: Context, modelAssetName: String) {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val labels = listOf(
        "Aluminium foil", "Bottle cap", "Bottle", "Broken glass", "Can", "Carton", 
        "Cigarette", "Cup", "Lid", "Other litter", "Other plastic", "Paper", 
        "Plastic bag - wrapper", "Plastic container", "Pop tab", "Straw", 
        "Styrofoam piece", "Unlabeled litter"
    )

    init {
        try {
            val modelBytes = context.assets.open(modelAssetName).readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            android.util.Log.d("ObjectDetector", "ONNX Session initialized with $modelAssetName")
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Failed to initialize ONNX session", e)
        }
    }

    fun detect(
        bitmap: Bitmap,
        targetProfile: ObjectVisualProfile? = null,
        lastKnownBox: RectF? = null,
        targetLabel: String? = null
    ): Detection? {
        if (ortSession == null) return null

        val startTime = System.currentTimeMillis()
        
        // Resize bitmap to 416x416
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 416, 416, true)
        val imgData = preprocess(resizedBitmap)

        val inputName = ortSession?.inputNames?.iterator()?.next() ?: return null
        val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, longArrayOf(1, 3, 416, 416))
        
        val results = ortSession?.run(Collections.singletonMap(inputName, inputTensor))
        val output = results?.get(0)?.value as? Array<Array<FloatArray>> ?: return null

        // Post-processing YOLOv8
        var detection = processOutput(output[0], bitmap.width, bitmap.height)

        // Attach visual profile (Farbe, Größe, Aufbau) if detection is found
        if (detection != null) {
            val profile = extractVisualProfile(bitmap, detection.boundingBox)
            detection = detection.copy(visualProfile = profile)
        }

        // Fallback to Visual Profile Color & Motion Tracking if AI model misses frame while approaching
        if ((detection == null || detection.confidence < 0.25f) && targetProfile != null && lastKnownBox != null && targetLabel != null) {
            val trackedDetection = trackByVisualProfile(bitmap, targetProfile, lastKnownBox, targetLabel)
            if (trackedDetection != null) {
                detection = trackedDetection
            }
        }

        val endTime = System.currentTimeMillis()
        android.util.Log.d("ObjectDetector", "Detection took ${endTime - startTime}ms")
        
        return detection
    }

    private fun extractVisualProfile(bitmap: Bitmap, box: RectF): ObjectVisualProfile {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)

        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        val hsv = FloatArray(3)
        var sumH = 0f
        var sumS = 0f
        var sumV = 0f

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sumR += r
            sumG += g
            sumB += b

            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            sumH += hsv[0]
            sumS += hsv[1]
            sumV += hsv[2]
        }

        val count = pixels.size.coerceAtLeast(1)
        val avgR = (sumR / count).toInt()
        val avgG = (sumG / count).toInt()
        val avgB = (sumB / count).toInt()
        val avgColorHex = android.graphics.Color.rgb(avgR, avgG, avgB)

        val avgHue = sumH / count
        val avgSat = sumS / count
        val avgVal = sumV / count
        val colorName = getColorName(avgHue, avgSat, avgVal)

        return ObjectVisualProfile(
            dominantColorHex = avgColorHex,
            avgHue = avgHue,
            avgSaturation = avgSat,
            avgValue = avgVal,
            aspectRatio = width.toFloat() / height.coerceAtLeast(1).toFloat(),
            initialArea = (width * height).toFloat(),
            colorName = colorName
        )
    }

    private fun getColorName(hue: Float, saturation: Float, value: Float): String {
        if (saturation < 0.18f) {
            return when {
                value < 0.25f -> "Schwarz"
                value > 0.75f -> "Weiß/Silber"
                else -> "Grau/Metall"
            }
        }
        return when {
            hue < 15f || hue > 345f -> "Rot"
            hue < 45f -> "Orange/Braun"
            hue < 70f -> "Gelb"
            hue < 165f -> "Grün"
            hue < 250f -> "Blau"
            hue < 315f -> "Lila"
            else -> "Rosa"
        }
    }

    private fun trackByVisualProfile(
        bitmap: Bitmap,
        targetProfile: ObjectVisualProfile,
        lastKnownBox: RectF,
        targetLabel: String
    ): Detection? {
        val imgW = bitmap.width
        val imgH = bitmap.height

        val boxWidth = lastKnownBox.width()
        val boxHeight = lastKnownBox.height()

        val searchLeft = (lastKnownBox.left - boxWidth * 0.4f).toInt().coerceIn(0, imgW - 1)
        val searchTop = (lastKnownBox.top - boxHeight * 0.2f).toInt().coerceIn(0, imgH - 1)
        val searchRight = (lastKnownBox.right + boxWidth * 0.4f).toInt().coerceIn(searchLeft + 1, imgW)
        val searchBottom = (lastKnownBox.bottom + boxHeight * 0.5f).toInt().coerceIn(searchTop + 1, imgH)

        var minX = searchRight
        var maxX = searchLeft
        var minY = searchBottom
        var maxY = searchTop
        var matchCount = 0

        val hsv = FloatArray(3)
        val targetHue = targetProfile.avgHue
        val targetSat = targetProfile.avgSaturation

        for (y in searchTop until searchBottom step 3) {
            for (x in searchLeft until searchRight step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                android.graphics.Color.RGBToHSV(r, g, b, hsv)

                var hueDiff = abs(hsv[0] - targetHue)
                if (hueDiff > 180f) hueDiff = 360f - hueDiff

                val isSatMatch = if (targetSat < 0.18f) hsv[1] < 0.30f else abs(hsv[1] - targetSat) < 0.35f
                val isHueMatch = targetSat < 0.18f || hueDiff < 40f

                if (isHueMatch && isSatMatch) {
                    matchCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (matchCount > 10 && maxX > minX && maxY > minY) {
            val newBox = RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
            val profile = extractVisualProfile(bitmap, newBox)
            return Detection(
                boundingBox = newBox,
                label = targetLabel,
                confidence = 0.85f,
                visualProfile = profile
            )
        }

        return null
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * 416 * 416)
        val pixels = IntArray(416 * 416)
        bitmap.getPixels(pixels, 0, 416, 0, 0, 416, 416)
        
        val area = 416 * 416
        for (i in 0 until area) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            // Planar format: RRRRR..., GGGGG..., BBBBB...
            buffer.put(i, r)
            buffer.put(i + area, g)
            buffer.put(i + 2 * area, b)
        }
        buffer.rewind()
        return buffer
    }

    private fun processOutput(output: Array<FloatArray>, imgWidth: Int, imgHeight: Int): Detection? {
        var maxConf = -1f
        var bestDetection: Detection? = null
        var absoluteMaxConf = 0f

        val numElements = 3549 // for 416x416 (52x52 + 26x26 + 13x13)
        val rows = output.size // This is 4 (Box) + 18 (Classes) = 22
        val numClasses = rows - 4

        for (i in 0 until numElements) {
            var classConf = -1f
            var classIdx = -1
            
            for (j in 0 until numClasses) {
                val conf = output[j + 4][i]
                if (conf > classConf) {
                    classConf = conf
                    classIdx = j
                }
            }
            
            if (classConf > absoluteMaxConf) absoluteMaxConf = classConf

            // Confidence threshold set very low (0.01) for debugging
            if (classConf > 0.01f && classConf > maxConf) {
                maxConf = classConf
                
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]

                val left = (cx - w / 2) * imgWidth / 416
                val top = (cy - h / 2) * imgHeight / 416
                val right = (cx + w / 2) * imgWidth / 416
                val bottom = (cy + h / 2) * imgHeight / 416

                bestDetection = Detection(
                    RectF(left, top, right, bottom),
                    labels.getOrElse(classIdx) { "Trash" },
                    classConf
                )
            }
        }
        android.util.Log.d("ObjectDetector", "Max confidence found: $absoluteMaxConf")
        return bestDetection
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
