package com.mylibrary.app.cloud

import com.mylibrary.core.NetworkUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // الرفع الكبير
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    /** يترجم أعطال الشبكة إلى [NetworkUnavailableException] كي لا تُحتسب فشلًا دائمًا. */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: UnknownHostException) { throw NetworkUnavailableException(e) }
        catch (e: ConnectException) { throw NetworkUnavailableException(e) }
        catch (e: SocketTimeoutException) { throw NetworkUnavailableException(e) }
    }

    fun exec(req: Request): Pair<Int, String> =
        client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }

    fun execOk(req: Request): String {
        val (c, b) = exec(req)
        if (c !in 200..299) throw HttpException(c, b)
        return b
    }

    fun jsonBody(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON)

    /** جسم طلب تدفقي من InputStream بحجم معروف (لا يُحمَّل في الذاكرة). */
    fun streamBody(type: String, length: Long, open: () -> InputStream): RequestBody = object : RequestBody() {
        override fun contentType() = type.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) { open().use { sink.writeAll(it.source()) } }
    }

    fun fileBody(type: String, f: File): RequestBody = streamBody(type, f.length()) { f.inputStream() }
}
