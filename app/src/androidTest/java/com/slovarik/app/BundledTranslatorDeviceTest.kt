package com.slovarik.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledTranslatorDeviceTest {
    @Test fun packagedMetadataDoesNotIncludeModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(context.assets.list("translation/en-ru").orEmpty().contains("encoder_model_quantized.onnx"))
        assertFalse(context.assets.list("translation/ru-en").orEmpty().contains("decoder_model_quantized.onnx"))
        assertTrue(context.assets.list("translation").orEmpty().contains("manifest.json"))
        val entries = TranslationModels.entries(context.assets.open("translation/manifest.json").bufferedReader().use { it.readText() })
        assertEquals(8, entries.size)
        assertTrue(entries.all { it.url().startsWith("https://huggingface.co/") })
    }
}
