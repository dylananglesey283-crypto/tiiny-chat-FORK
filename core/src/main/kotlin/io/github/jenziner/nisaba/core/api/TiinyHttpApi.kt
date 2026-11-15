package io.github.jenziner.nisaba.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp-Umsetzung von [ChatApi].
 *
 * [baseUrl] ist die konfigurierte Basisadresse inklusive `/v1`,
 * z. B. `http://172.19.178.109/v1`. Der API-Key wird nur im Header
 * `Authorization: Bearer …` übertragen und nie protokolliert.
 * Das [client] wird von aussen übergeben, damit die Android-Schicht es an
 * ein bestimmtes Netzwerk (USB) binden kann.
 */
class TiinyHttpApi(
    baseUrl: String,
    private val apiKey: () -> String?,
    private val client: OkHttpClient,
) : ChatApi {
    private val base: HttpUrl = requireNotNull(baseUrl.trim().trimEnd('/').toHttpUrlOrNull()) { "Invalid base address" }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(url(path)).header("Accept", "application/json")
        apiKey()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    override suspend fun listModels(): ModelsResult {
        val call = client.newCall(request("models").get().build())
        return try {
            call.await().use { resp ->
                val body = withContext(Dispatchers.IO) { resp.body.string() }
                if (!resp.isSuccessful) return ModelsResult.Err(OpenAiWire.httpError(resp.code, body))
                OpenAiWire.parseModels(body)?.let { ModelsResult.Ok(it) }
                    ?: ModelsResult.Err(ApiError.Protocol("Model list not readable"))
            }
        } catch (e: IOException) {
            ModelsResult.Err(e.toApiError())
        }
    }

    override suspend fun chat(request: ChatRequest, onEvent: (ChatEvent) -> Unit): ChatOutcome {
        val body = OpenAiWire.encodeRequest(request).toRequestBody("application/json".toMediaType())
        val builder = request("chat/completions").post(body)
        if (request.stream) builder.header("Accept", "text/event-stream")
        val call = client.newCall(builder.build())
        val parser = OpenAiWire.StreamParser(onEvent)
        return try {
            call.await().use { resp ->
                if (!resp.isSuccessful) {
                    val text = withContext(Dispatchers.IO) { resp.body.string() }
                    return ChatOutcome.Failed(OpenAiWire.httpError(resp.code, text))
                }
                val isStream = resp.header("Content-Type")?.startsWith("text/event-stream") == true
                if (!isStream) {
                    val text = withContext(Dispatchers.IO) { resp.body.string() }
                    val outcome = OpenAiWire.parseCompletion(text)
                    outcome.model?.let { onEvent(ChatEvent.Model(it)) }
                    OpenAiWire.parseReasoning(text)?.let { onEvent(ChatEvent.ReasoningDelta(it)) }
                    if (outcome.content.isNotEmpty()) onEvent(ChatEvent.ContentDelta(outcome.content))
                    return outcome
                }
                withContext(Dispatchers.IO) {
                    val source = resp.body.source()
                    try {
                        while (true) {
                            ensureActive()
                            val line = source.readUtf8Line() ?: break
                            parser.feedLine(line)
                        }
                        parser.finish()
                    } catch (e: IOException) {
                        if (parser.partial("x").content.isEmpty()) {
                            ChatOutcome.Failed(e.toApiError())
                        } else {
                            parser.partial(if (e is SocketTimeoutException) "timeout" else "stream_ended")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            ChatOutcome.Failed(e.toApiError())
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        }
    }
}

private fun IOException.toApiError(): ApiError = when (this) {
    is SocketTimeoutException -> ApiError.Timeout(message ?: "Timeout")
    is InterruptedIOException -> ApiError.Timeout(message ?: "Timeout")
    else -> ApiError.Connection(message ?: javaClass.simpleName)
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
