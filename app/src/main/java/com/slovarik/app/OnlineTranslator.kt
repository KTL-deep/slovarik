package com.slovarik.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object OnlineTranslator {
    fun withinLimit(text: String) = text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 500
    fun parse(json: String): String {
        val response = JSONObject(json)
        check(response.optInt("responseStatus") == 200) { "MyMemory: ${response.optString("responseDetails").take(180)}" }
        return response.getJSONObject("responseData").getString("translatedText").trim().also { check(it.isNotEmpty()) { "Empty translation" } }
    }

    fun translate(context: Context, text: String, source: String, target: String): String {
        require(BundledTranslator.supports(source, target)) { context.getString(R.string.translation_languages) }
        require(withinLimit(text)) { context.getString(R.string.online_translation_limit) }
        val query = URLEncoder.encode(text, "UTF-8")
        val connection = URL("https://api.mymemory.translated.net/get?q=$query&langpair=$source%7C$target").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            check(connection.responseCode == 200) { "MyMemory HTTP ${connection.responseCode}" }
            check(connection.url.protocol == "https") { "Insecure translation redirect" }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(64_001)
                val result = StringBuilder()
                while (result.length <= 64_000) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, 64_001 - result.length))
                    if (count < 0) break
                    result.append(buffer, 0, count)
                }
                check(result.length <= 64_000) { "MyMemory response too large" }
                result.toString()
            }
            return try { parse(body) } catch (error: IllegalStateException) {
                throw IllegalStateException(context.getString(R.string.online_translation_retry, error.message))
            }
        } finally { connection.disconnect() }
    }
}
