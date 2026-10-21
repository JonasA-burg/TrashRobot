package com.example.trashrobot

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ObjectDetectorTest {

    @Test
    fun testDetectionDataClass() {
        val boundingBox = RectF(10f, 10f, 100f, 100f)
        val detection = Detection(boundingBox, "person", 0.9f)

        assertEquals("person", detection.label)
        assertEquals(0.9f, detection.confidence)
        assertEquals(10f, detection.boundingBox.left)
    }
}