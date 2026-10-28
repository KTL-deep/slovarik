package com.slovarik.app

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.io.FileInputStream
import java.nio.LongBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Offline OPUS-MT inference. Both directions are installed inside the APK.
 * One direction is resident at a time; all native tensors/results are closed per request.
 * Call translate on a background dispatcher, never on the Android UI thread.
 */
internal class BundledTranslator(context: Context) : Closeable {
    private val assets = context.applicationContext.assets
    private val environment = OrtEnvironment.getEnvironment()
    private var loaded: Model? = null

    private class Model(
        val direction: String,
        val encoderBytes: MappedByteBuffer,
        val decoderBytes: MappedByteBuffer,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val tokenizer: UnigramTokenizer,
        val startId: Long,
        val eosId: Long,
        val padId: Long,
        val vocabulary: Int,
    ) : Closeable {
        override fun close() { try { encoder.close() } finally { decoder.close() } }
    }

    private fun map(path: String): MappedByteBuffer = assets.openFd(path).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
    }

    private fun load(direction: String): Model {
        loaded?.takeIf { it.direction == direction }?.let { return it }
        loaded?.close()
        loaded = null
        val prefix = "translation/$direction"
        val config = assets.open("$prefix/config.json").bufferedReader().use { JSONObject(it.readText()) }
        val tokenizerJson = assets.open("$prefix/tokenizer.json").bufferedReader().use { JSONObject(it.readText()) }
        val vocab = tokenizerJson.getJSONObject("model").getJSONArray("vocab")
        val pieces = List(vocab.length()) { vocab.getJSONArray(it).getString(0) }
        val scores = DoubleArray(vocab.length()) { vocab.getJSONArray(it).getDouble(1) }
        val eos = config.getLong("eos_token_id")
        val pad = config.getLong("pad_token_id")
        val tokenizer = UnigramTokenizer(pieces, scores, pieces.indexOf("<unk>"), eos.toInt(), pad.toInt())
        val encoderBytes = map("$prefix/encoder_model_quantized.onnx")
        val decoderBytes = map("$prefix/decoder_model_quantized.onnx")
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            val encoder = environment.createSession(encoderBytes, options)
            val decoder = try { environment.createSession(decoderBytes, options) }
                catch (error: Throwable) { encoder.close(); throw error }
            return Model(direction, encoderBytes, decoderBytes, encoder, decoder, tokenizer,
                config.getLong("decoder_start_token_id"), eos, pad, pieces.size).also { loaded = it }
        }
    }

    @Synchronized
    fun translate(text: String, source: String, target: String): String {
        require(supports(source, target)) { "Встроенный перевод поддерживает английский ↔ русский" }
        val model = load("$source-$target")
        val ids = model.tokenizer.encode(text)
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { mask ->
                model.encoder.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { encoded ->
                    val hidden = encoded.get("last_hidden_state").get() as OnnxTensor
                    val output = ArrayList<Long>()
                    output += model.startId
                    var finished = false
                    repeat(48) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Перевод отменён")
                        val decoderIds = output.toLongArray()
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(decoderIds), longArrayOf(1, decoderIds.size.toLong())).use { tokens ->
                            model.decoder.run(mapOf("input_ids" to tokens, "encoder_hidden_states" to hidden,
                                "encoder_attention_mask" to mask), setOf("logits")).use { decoded ->
                                val logits = (decoded.get("logits").get() as OnnxTensor).floatBuffer
                                logits.position((decoderIds.size - 1) * model.vocabulary)
                                var bestId = model.eosId
                                var best = Float.NEGATIVE_INFINITY
                                for (id in 0 until model.vocabulary) {
                                    val score = logits.get()
                                    if (id.toLong() != model.padId && score > best) { best = score; bestId = id.toLong() }
                                }
                                if (bestId == model.eosId) finished = true else output += bestId
                            }
                        }
                        if (finished) return model.tokenizer.decode(output.drop(1)).also {
                            check(it.isNotBlank()) { "Не удалось подобрать перевод. Попробуйте другую форму слова." }
                        }
                    }
                    error("Слишком длинный результат. Попробуйте более короткую фразу.")
                }
            }
        }
    }

    @Synchronized override fun close() { loaded?.close(); loaded = null }

    companion object {
        fun supports(source: String, target: String) = (source == "en" && target == "ru") || (source == "ru" && target == "en")
    }
}
