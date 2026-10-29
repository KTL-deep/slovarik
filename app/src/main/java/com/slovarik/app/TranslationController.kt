package com.slovarik.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serial inference and asynchronous cleanup: loading/closing models never blocks UI. */
internal class TranslationController(context: Context) : Closeable {
    private val app = context.applicationContext
    private val translator = BundledTranslator(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    fun translate(text: String, source: String, target: String, callback: (Result<String>) -> Unit) {
        if (closed.get()) return
        executor.execute {
            val result = try { Result.success(if (TranslationModels.needsRepair(app)) throw IllegalStateException(TranslationModels.repairMessage(app))
                else if (TranslationModels.useOffline(TranslationModels.isInstalled(app))) translator.translate(text, source, target)
                else OnlineTranslator.translate(app, text, source, target)) }
                catch (error: Exception) { Result.failure(error) }
            main.post { if (!closed.get()) callback(result) }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.execute { translator.close() }
            executor.shutdown()
        }
    }
}
