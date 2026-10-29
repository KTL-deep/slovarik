package com.slovarik.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object TranslationModels {
    const val workName = "translation-model-download"
    val revision = MutableStateFlow(0)
    private val names = listOf("encoder_model_quantized.onnx", "decoder_model_quantized.onnx", "tokenizer.json", "config.json")
    private val directions = listOf("en-ru", "ru-en")
    fun useOffline(installed: Boolean) = installed
    data class Entry(val path: String, val sha256: String, val bytes: Long, val revision: String) {
        fun url(): String {
            val (direction, name) = path.split('/')
            val remote = if (name.endsWith(".onnx")) "onnx/$name" else name
            return "https://huggingface.co/Xenova/opus-mt-$direction/resolve/$revision/$remote"
        }
    }

    fun entries(json: String): List<Entry> {
        val manifest = JSONObject(json)
        val files = manifest.getJSONArray("files")
        val models = manifest.getJSONObject("models")
        val expected = directions.flatMap { direction -> names.map { "$direction/$it" } }.toSet()
        val result = (0 until files.length()).map { index ->
            val item = files.getJSONObject(index)
            val path = item.getString("path")
            require(path in expected) { "Unknown model file: $path" }
            val sha = item.getString("sha256")
            val bytes = item.getLong("bytes")
            val revision = models.getJSONObject(path.substringBefore('/')).getString("revision")
            require(sha.matches(Regex("[a-f0-9]{64}")) && bytes in 1..100_000_000 && revision.matches(Regex("[a-f0-9]{40}"))) { "Invalid model manifest" }
            Entry(path, sha, bytes, revision)
        }
        require(result.map { it.path }.toSet() == expected && result.size == expected.size) { "Incomplete model manifest" }
        return result
    }

    fun installedRoot(context: Context) = File(context.noBackupFilesDir, "translation-models")
    private fun repairFlag(context: Context) = File(installedRoot(context), "repair-required")
    fun needsRepair(context: Context) = repairFlag(context).isFile
    fun repairMessage(context: Context) = com.slovarik.app.InterfaceLanguage.wrap(context).getString(R.string.translation_model_corrupt)
    private fun manifest(context: Context) = context.assets.open("translation/manifest.json").bufferedReader().use { it.readText() }
    fun isInstalled(context: Context): Boolean {
        val root = installedRoot(context)
        val manifest = manifest(context)
        val stamp = File(root, "installed.sha256")
        return !needsRepair(context) && stamp.isFile && stamp.readText() == digest(manifest.toByteArray()) &&
            entries(manifest).all { File(root, it.path).length() == it.bytes }
    }
    /** Invoked only by the inference executor before loading a new direction. Never hash on the UI thread. */
    fun requireVerifiedDirection(context: Context, direction: String) {
        val valid = try {
            entries(manifest(context)).filter { it.path.startsWith("$direction/") }.let { files ->
                files.size == names.size && files.all { verified(File(installedRoot(context), it.path), it) }
            }
        } catch (_: Exception) { false }
        if (!valid) {
            File(installedRoot(context), "installed.sha256").delete()
            repairFlag(context).writeText("1")
            revision.value++
            throw IllegalStateException(repairMessage(context))
        }
    }
    fun start(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ModelDownloadWorker>().build())
    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(workName)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verified(file: File, entry: Entry) = matchesModelDigest(file, entry.bytes, entry.sha256)

    internal fun matchesModelDigest(file: File, bytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    suspend fun download(context: Context, stopped: () -> Boolean, progress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        val json = manifest(context)
        val entries = entries(json)
        val root = installedRoot(context)
        root.mkdirs()
        entries.forEachIndexed { index, entry ->
            if (stopped()) throw InterruptedException("Download cancelled")
            val target = File(root, entry.path)
            if (!verified(target, entry)) {
                target.parentFile!!.mkdirs()
                val temp = File(target.parentFile, "${target.name}.part")
                temp.delete()
                try {
                    val connection = URL(entry.url()).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    try {
                        check(connection.responseCode == 200) { "Model download HTTP ${connection.responseCode}" }
                        check(connection.url.protocol == "https") { "Insecure model redirect" }
                        val hash = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        connection.inputStream.use { input -> temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (stopped()) throw InterruptedException("Download cancelled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                size += n
                                check(size <= entry.bytes) { "Model file exceeds expected size" }
                                hash.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        } }
                        check(size == entry.bytes && hash.digest().joinToString("") { "%02x".format(it) } == entry.sha256) { "Model checksum mismatch" }
                        check(temp.renameTo(target)) { "Could not install model file" }
                    } finally { connection.disconnect() }
                } finally { temp.delete() }
            }
            progress((index + 1) * 100 / entries.size)
        }
        if (stopped()) throw InterruptedException("Download cancelled")
        val stamp = File(root, "installed.sha256.part")
        stamp.writeText(digest(json.toByteArray()))
        check(stamp.renameTo(File(root, "installed.sha256"))) { "Could not finish model installation" }
        repairFlag(context).delete()
        revision.value++
    }
}

internal class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        TranslationModels.download(applicationContext, { isStopped }) { setProgress(workDataOf("percent" to it)) }
        Result.success()
    } catch (error: Exception) {
        if (isStopped) Result.failure() else if (runAttemptCount < 2) Result.retry()
        else Result.failure(workDataOf("error" to (error.message ?: "Download failed")))
    }
}
