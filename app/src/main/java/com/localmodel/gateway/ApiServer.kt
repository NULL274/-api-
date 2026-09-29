package com.localmodel.gateway

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

class ApiServer(
    bindHost: String,
    port: Int,
    private val apiKey: String?,
    private val modelId: String,
) : NanoHTTPD(bindHost, port) {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Msg(val role: String, val content: JsonElement? = null)

    private data class Attachment(
        val text: String,
        val images: List<ByteArray>,
        val audio: List<ByteArray>,
    )

    data class ChatRequest(
        val model: String? = null,
        val messages: List<Msg> = emptyList(),
        val stream: Boolean = false,
        val temperature: Double? = null,
        @SerializedName("top_p") val topP: Double? = null,
        @SerializedName("top_k") val topK: Int? = null,
    )

    override fun serve(session: IHTTPSession): Response {
        val response = route(session)
        DiagLog.d("HTTP ${session.method} ${session.uri} -> ${response.status?.requestStatus ?: "?"}")
        return response
    }

    private fun currentModelId(): String =
        LlmEngine.loadedModelPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: modelId

    private fun route(session: IHTTPSession): Response {
        if (!AppSignature.isIntact()) {
            return json(Response.Status.INTERNAL_ERROR, error("signature check failed"))
        }
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        val uri = session.uri
        if ((uri == "/" || uri == "/index.html") && session.method == Method.GET) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", infoText())
        }
        if (uri == "/favicon.ico") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        }

        if (!apiKey.isNullOrBlank()) {
            val token = (session.headers["authorization"] ?: "").removePrefix("Bearer ").trim()
            if (token != apiKey) {
                return cors(json(Response.Status.UNAUTHORIZED, error("Invalid API key")))
            }
        }

        return when {
            uri.endsWith("/models") && session.method == Method.GET -> cors(handleModels())
            uri.contains("/models/") && session.method == Method.GET -> cors(handleModels())
            uri.endsWith("/chat/completions") && session.method == Method.POST -> handleChat(session)
            else -> cors(json(Response.Status.NOT_FOUND, error("Unknown endpoint: $uri")))
        }
    }

    private fun infoText(): String = buildString {
        appendLine("本地小模型接入工具 - OpenAI 兼容反代")
        appendLine()
        appendLine("Base URL 填:      http://<本机IP>:<端口>/v1")
        appendLine("完整路径填:      http://<本机IP>:<端口>/v1/chat/completions")
        appendLine("模型名填:        ${currentModelId()}")
        appendLine("API Key 填:      ${if (apiKey.isNullOrBlank()) "（未启用校验，随便填）" else "应用设置页里的那串 sk-..."}")
        appendLine()
        appendLine("可用接口:")
        appendLine("  GET  /v1/models            列出模型")
        appendLine("  POST /v1/chat/completions  对话补全（支持 stream）")
        appendLine()
        appendLine("鉴权头: Authorization: Bearer <API Key>")
        appendLine()
        appendLine(AppSignature.text())
    }

    override fun stop() {
        runCatching { scope.cancel() }
        super.stop()
    }

    private fun handleModels(): Response {
        val id = currentModelId()
        val payload = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf(
                    "id" to id,
                    "object" to "model",
                    "created" to System.currentTimeMillis() / 1000,
                    "owned_by" to "localmodel",
                )
            ),
        )
        return json(Response.Status.OK, gson.toJson(payload))
    }

    private fun handleChat(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return cors(json(Response.Status.BAD_REQUEST, error("无法解析请求体")))
        }
        val request = runCatching { gson.fromJson(files["postData"], ChatRequest::class.java) }
            .getOrNull()
            ?: return cors(json(Response.Status.BAD_REQUEST, error("请求体不是合法 JSON")))
        if (request.messages.isEmpty()) {
            return cors(json(Response.Status.BAD_REQUEST, error("messages 不能为空")))
        }
        if (!LlmEngine.isLoaded) {
            return cors(json(Response.Status.SERVICE_UNAVAILABLE, error("App 内尚未加载模型")))
        }

        val turns = ArrayList<LlmEngine.Turn>(request.messages.size)
        var images: List<ByteArray> = emptyList()
        var audio: List<ByteArray> = emptyList()
        request.messages.forEachIndexed { index, message ->
            val parsed = parseContent(message.content)
            turns.add(LlmEngine.Turn(message.role, parsed.text))
            if (index == request.messages.lastIndex) {
                images = parsed.images
                audio = parsed.audio
            }
        }
        val override = LlmEngine.SamplerOverride(request.topK, request.topP, request.temperature)
        return if (request.stream) {
            streamChat(turns, override, images, audio)
        } else {
            blockChat(turns, override, images, audio)
        }
    }

    private fun blockChat(
        turns: List<LlmEngine.Turn>,
        override: LlmEngine.SamplerOverride,
        images: List<ByteArray>,
        audio: List<ByteArray>,
    ): Response {
        val text = StringBuilder()
        try {
            runBlocking {
                LlmEngine.complete(turns, override, images, audio)
                    .collect { chunk -> text.append(chunk.text) }
            }
        } catch (t: Throwable) {
            val message = t.message ?: "推理失败"
            return cors(json(errorStatus(message), error(message)))
        }

        val payload = mapOf(
            "id" to "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
            "object" to "chat.completion",
            "created" to System.currentTimeMillis() / 1000,
            "model" to currentModelId(),
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "message" to mapOf("role" to "assistant", "content" to text.toString()),
                    "finish_reason" to "stop",
                )
            ),
            "usage" to mapOf(
                "prompt_tokens" to 0,
                "completion_tokens" to 0,
                "total_tokens" to 0,
            ),
        )
        return cors(json(Response.Status.OK, gson.toJson(payload)))
    }

    private fun streamChat(
        turns: List<LlmEngine.Turn>,
        override: LlmEngine.SamplerOverride,
        images: List<ByteArray>,
        audio: List<ByteArray>,
    ): Response {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 1 shl 20)
        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", input)
        cors(response)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000

        scope.launch {
            val writer = BufferedWriter(OutputStreamWriter(out, "UTF-8"))
            try {
                writer.write(chunk(id, created, role = "assistant", content = null, finish = null))
                writer.flush()
                LlmEngine.complete(turns, override, images, audio).collect { piece ->
                    if (piece.text.isNotEmpty()) {
                        writer.write(chunk(id, created, role = null, content = piece.text, finish = null))
                        writer.flush()
                    }
                }
                writer.write(chunk(id, created, role = null, content = null, finish = "stop"))
                writer.write("data: [DONE]\n\n")
                writer.flush()
            } catch (t: Throwable) {
                runCatching {
                    writer.write(
                        "data: " + gson.toJson(
                            mapOf(
                                "error" to mapOf(
                                    "message" to (t.message ?: "推理失败"),
                                    "type" to "invalid_request_error",
                                )
                            )
                        ) + "\n\n"
                    )
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                }
            } finally {
                runCatching { out.close() }
            }
        }
        return response
    }

    private fun chunk(id: String, created: Long, role: String?, content: String?, finish: String?): String {
        val delta = LinkedHashMap<String, Any>()
        if (role != null) delta["role"] = role
        if (content != null) delta["content"] = content
        val payload = LinkedHashMap<String, Any>()
        payload["id"] = id
        payload["object"] = "chat.completion.chunk"
        payload["created"] = created
        payload["model"] = currentModelId()
        payload["choices"] = listOf(
            mapOf("index" to 0, "delta" to delta, "finish_reason" to finish)
        )
        return "data: " + gson.toJson(payload) + "\n\n"
    }

    private fun parseContent(element: JsonElement?): Attachment {
        val text = StringBuilder()
        val images = ArrayList<ByteArray>()
        val audio = ArrayList<ByteArray>()
        when {
            element == null || element.isJsonNull -> Unit
            element.isJsonPrimitive -> text.append(element.asString)
            element.isJsonArray -> element.asJsonArray.forEach { part ->
                val obj = runCatching { part.asJsonObject }.getOrNull() ?: return@forEach
                when (obj.get("type")?.asString) {
                    "text" -> text.append(obj.get("text")?.asString.orEmpty())
                    "image_url" -> decodeDataUrl(
                        obj.getAsJsonObject("image_url")?.get("url")?.asString,
                    )?.let(images::add)

                    "input_audio" -> obj.getAsJsonObject("input_audio")
                        ?.get("data")?.asString
                        ?.let { decodeBase64(it) }
                        ?.let(audio::add)
                }
            }
        }
        return Attachment(text.toString(), images, audio)
    }

    private fun decodeDataUrl(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        return decodeBase64(url.substring(comma + 1))
    }

    private fun decodeBase64(data: String): ByteArray? =
        runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()

    private fun errorStatus(message: String): Response.Status =
        if (message.contains("too long", ignoreCase = true) ||
            message.contains("exceed", ignoreCase = true) ||
            message.contains("messages 为空", ignoreCase = true)
        ) {
            Response.Status.BAD_REQUEST
        } else {
            Response.Status.INTERNAL_ERROR
        }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun error(message: String): String =
        gson.toJson(mapOf("error" to mapOf("message" to message, "type" to "invalid_request_error")))

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
