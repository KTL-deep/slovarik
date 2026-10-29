package com.slovarik.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class TranslationModelsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sameLengthCorruptionFailsDigestVerification() {
        val model = folder.newFile("model.onnx")
        model.writeBytes(byteArrayOf(1, 2, 3, 4))
        val expected = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(TranslationModels.matchesModelDigest(model, 4, expected))
        model.writeBytes(byteArrayOf(1, 2, 3, 5))
        assertEquals(4, model.length())
        assertFalse(TranslationModels.matchesModelDigest(model, 4, expected))
    }
    @Test fun remotePathsUsePinnedRevision() {
        val revision = "a".repeat(40)
        assertEquals("https://huggingface.co/Xenova/opus-mt-en-ru/resolve/$revision/onnx/encoder_model_quantized.onnx",
            TranslationModels.Entry("en-ru/encoder_model_quantized.onnx", "", 10, revision).url())
        assertEquals("https://huggingface.co/Xenova/opus-mt-ru-en/resolve/$revision/tokenizer.json",
            TranslationModels.Entry("ru-en/tokenizer.json", "", 10, revision).url())
    }

    @Test fun offlineOnlyWhenInstalledAndOnlineHasByteLimit() {
        assertTrue(TranslationModels.useOffline(true))
        assertFalse(TranslationModels.useOffline(false))
        assertTrue(OnlineTranslator.withinLimit("я".repeat(250)))
        assertFalse(OnlineTranslator.withinLimit("я".repeat(251)))
        assertFalse(OnlineTranslator.withinLimit(" "))
    }
}
