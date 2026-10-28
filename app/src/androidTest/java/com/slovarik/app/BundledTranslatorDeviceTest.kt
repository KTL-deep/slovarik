package com.slovarik.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledTranslatorDeviceTest {
    @Test fun packagedModelsTranslateBothDirectionsWithoutAnyDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.assets.list("translation/en-ru")!!.contains("encoder_model_quantized.onnx"))
        assertTrue(context.assets.list("translation/ru-en")!!.contains("decoder_model_quantized.onnx"))
        BundledTranslator(context).use { engine ->
            val russian = engine.translate("book", "en", "ru").lowercase()
            assertTrue("Expected a Russian translation, got: $russian", russian.contains("книг"))
            val english = engine.translate("книга", "ru", "en").lowercase()
            assertTrue("Expected an English translation, got: $english", english.contains("book"))
            assertTrue(engine.translate("house", "en", "ru").lowercase().contains("дом"))
        }
    }
}
