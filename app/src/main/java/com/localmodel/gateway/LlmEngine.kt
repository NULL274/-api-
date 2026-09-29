package com.localmodel.gateway

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codeshipping.llamakotlin.LlamaConfig
import org.codeshipping.llamakotlin.LlamaModel

object LlmEngine {

    enum class Accelerator { CPU, GPU, NPU }

    enum class Runtime { NONE, LITERT, LLAMA_CPP }

    const val THOUGHT_CHANNEL = "thought"

    data class Params(
        val accelerator: Accelerator,
        val maxTokens: Int,
        val topK: Int,
        val topP: Double,
        val temperature: Double,
        val systemPrompt: String,
        val enableThinking: Boolean,

        val enableVision: Boolean = false,
    )

    data class Chunk(val text: String, val thinking: String?)

    data class Turn(val role: String, val content: String)

    data class SamplerOverride(val topK: Int?, val topP: Double?, val temperature: Double?)

    private val mutex = Mutex()

    private var runtime = Runtime.NONE

    private var engine: Engine? = null
    private var chatConversation: Conversation? = null

    private var llama: LlamaModel? = null
    private val llamaHistory = ArrayList<Turn>()
    private var llamaFamily = PromptFormat.Family.CHATML

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var loadedModelPath: String? = null
        private set

    @Volatile
    var loadedParams: Params? = null
        private set

    @Volatile
    var activeBackend: String? = null
        private set

    @Volatile
    var note: String? = null
        private set

    val isLoaded: Boolean
        get() = runtime != Runtime.NONE

    suspend fun load(context: Context, modelPath: String, params: Params): Result<Unit> = mutex.withLock {
        closeInternal()
        appContext = context.applicationContext
        runCatching {
            if (modelPath.endsWith(".gguf", ignoreCase = true)) {
                loadLlama(modelPath, params)
            } else {
                loadLiteRt(context, modelPath, params)
            }
            loadedModelPath = modelPath
            loadedParams = params
        }
    }

    suspend fun unload() = mutex.withLock { closeInternal() }

    fun chat(prompt: String, images: List<ByteArray> = emptyList()): Flow<Chunk> = flow {
        var retriedWithCpu = false
        while (true) {
            val forwarded = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                mutex.withLock {
                    when (runtime) {
                        Runtime.LITERT -> {
                            val conversation = chatConversation ?: throw IllegalStateException("模型未加载")
                            emitAll(
                                liteRtStream(conversation, contentsOf(prompt, images), extraContext())
                                    .onEach { forwarded.set(true) }
                            )
                        }

                        Runtime.LLAMA_CPP -> {
                            if (images.isNotEmpty()) {
                                throw IllegalStateException("GGUF 运行时不支持图像输入，请改用 .litertlm 多模态模型")
                            }
                            emitAll(llamaChat(prompt).onEach { forwarded.set(true) })
                        }

                        Runtime.NONE -> throw IllegalStateException("模型未加载")
                    }
                }
                return@flow
            } catch (t: Throwable) {
                val canRetry = !retriedWithCpu && !forwarded.get() && isAcceleratorFailure(t)
                if (!canRetry) throw t
                retriedWithCpu = true
                DiagLog.w("加速器推理失败，自动改用 CPU 重试", t)
                if (!mutex.withLock { switchToCpu() }) throw t
            }
        }
    }

    fun complete(
        turns: List<Turn>,
        override: SamplerOverride?,
        images: List<ByteArray> = emptyList(),
        audio: List<ByteArray> = emptyList(),
    ): Flow<Chunk> = flow {
        mutex.withLock {
            when (runtime) {
                Runtime.LITERT -> emitAll(liteRtComplete(turns, override, images, audio))
                Runtime.LLAMA_CPP -> {
                    if (images.isNotEmpty() || audio.isNotEmpty()) {
                        throw IllegalStateException("GGUF 运行时不支持图像/音频输入")
                    }
                    emitAll(llamaComplete(turns, override))
                }

                Runtime.NONE -> throw IllegalStateException("模型未加载")
            }
        }
    }

    suspend fun resetChat() = mutex.withLock {
        when (runtime) {
            Runtime.LITERT -> {
                val current = engine ?: return@withLock
                val params = loadedParams ?: return@withLock
                val previous = chatConversation
                chatConversation = current.createConversation(conversationConfig(params))
                runCatching { previous?.close() }
            }

            Runtime.LLAMA_CPP -> llamaHistory.clear()

            Runtime.NONE -> Unit
        }
    }

    fun stop() {
        when (runtime) {
            Runtime.LITERT -> runCatching { chatConversation?.cancelProcess() }
            Runtime.LLAMA_CPP -> runCatching { llama?.cancelGeneration() }
            Runtime.NONE -> Unit
        }
    }

    private fun loadLiteRt(context: Context, modelPath: String, params: Params) {
        val chain = when (params.accelerator) {
            Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.CPU)
            Accelerator.NPU -> listOf(Accelerator.NPU, Accelerator.CPU)
            Accelerator.CPU -> listOf(Accelerator.CPU)
        }

        var lastError: Throwable? = null
        chain.forEachIndexed { index, accelerator ->
            var created: Engine? = null
            try {
                DiagLog.d("LiteRT 初始化尝试 backend=$accelerator vision=${params.enableVision} maxNumTokens=${params.maxTokens} model=$modelPath")
                created = Engine(engineConfig(context, modelPath, params, accelerator))
                created.initialize()
                val conversation = created.createConversation(conversationConfig(params))
                DiagLog.d("LiteRT 引擎初始化成功 backend=$accelerator")
                engine = created
                chatConversation = conversation
                runtime = Runtime.LITERT
                activeBackend = accelerator.name
                if (index > 0) {
                    note = "所选加速器初始化失败，已自动回退到 ${accelerator.name}"
                }
                return
            } catch (t: Throwable) {
                runCatching { created?.close() }
                DiagLog.w("LiteRT 初始化失败 backend=$accelerator", t)
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("引擎初始化失败")
    }

    private fun engineConfig(
        context: Context,
        modelPath: String,
        params: Params,
        accelerator: Accelerator,
    ): EngineConfig {
        val backend = when (accelerator) {
            Accelerator.CPU -> Backend.CPU()
            Accelerator.GPU -> Backend.GPU()
            Accelerator.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        }
        val visionEnabled = params.enableVision
        return EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = if (visionEnabled) backend else null,
            audioBackend = if (visionEnabled) Backend.CPU() else null,
            maxNumTokens = params.maxTokens,
            maxNumImages = null,
            cacheDir = null,
        )
    }

    private fun conversationConfig(params: Params) = ConversationConfig(
        systemInstruction = params.systemPrompt.takeIf { it.isNotBlank() }
            ?.let { Contents.of(SignatureCodec.guard(it)) },
        initialMessages = emptyList(),
        tools = emptyList(),
        samplerConfig = SamplerConfig(
            topK = params.topK,
            topP = params.topP,
            temperature = params.temperature,
        ),
        automaticToolCalling = false,
        channels = emptyList(),
        extraContext = emptyMap(),
        loraConfig = null,
        prefillPrefaceOnInit = false,
        maxOutputToken = null,
        thinkingConfig = null,
        enableResponseFormat = false,
    )

    private fun extraContext(): Map<String, Any> =
        mapOf("enable_thinking" to (loadedParams?.enableThinking ?: false))

    private fun contentsOf(text: String, images: List<ByteArray>, audio: List<ByteArray> = emptyList()): Contents {
        val safeText = SignatureCodec.guard(text)
        if (images.isEmpty() && audio.isEmpty()) return Contents.of(safeText)
        val parts = ArrayList<Content>()
        images.forEach { parts.add(Content.ImageBytes(it)) }
        audio.forEach { parts.add(Content.AudioBytes(it)) }
        if (safeText.isNotEmpty()) parts.add(Content.Text(safeText))
        return Contents.of(parts)
    }

    private fun estimateTokens(text: String): Int = (text.length / 1.5).toInt() + 1

    private fun clampSystem(system: String?, maxTokens: Int): String? {
        if (system.isNullOrEmpty()) return null
        val budget = (maxTokens * 0.5).toInt().coerceAtLeast(64)
        val estimated = estimateTokens(system)
        if (estimated <= budget) return system
        val keepChars = (system.length.toDouble() * budget / estimated).toInt().coerceAtLeast(1)
        DiagLog.w("系统提示词过长（估算 $estimated tokens / 预算 $budget），已截断到 $keepChars 字符")
        return system.take(keepChars)
    }

    private fun trimToBudget(
        systemIn: String?,
        body: List<Turn>,
        maxTokens: Int,
    ): Pair<String?, List<Turn>> {
        val system = clampSystem(systemIn, maxTokens)
        val budget = (maxTokens * 0.7).toInt().coerceAtLeast(256)
        var used = system?.let { estimateTokens(it) } ?: 0
        val kept = ArrayList<Turn>()
        for (turn in body.asReversed()) {
            val cost = estimateTokens(turn.content)
            if (kept.isNotEmpty() && used + cost > budget) break
            used += cost
            kept.add(0, turn)
        }
        if (kept.size != body.size) {
            DiagLog.w(
                "输入超出上下文预算（估算 $used tokens / 限额 $budget），已丢弃 ${body.size - kept.size} 条较早消息"
            )
        }
        return system to kept
    }

    private fun liteRtComplete(
        turns: List<Turn>,
        override: SamplerOverride?,
        images: List<ByteArray>,
        audio: List<ByteArray>,
    ): Flow<Chunk> = flow {
        val current = engine ?: throw IllegalStateException("模型未加载")
        val params = loadedParams ?: throw IllegalStateException("模型未加载")

        var system = params.systemPrompt.takeIf { it.isNotBlank() }
        val body = ArrayList<Turn>()
        for (turn in turns) {
            if (turn.role == "system") system = turn.content else body.add(turn)
        }
        if (body.isEmpty()) throw IllegalArgumentException("messages 为空")
        val (finalSystem, trimmed) = trimToBudget(system, body, params.maxTokens)

        val config = ConversationConfig(
            systemInstruction = finalSystem?.let { Contents.of(SignatureCodec.guard(it)) },
            initialMessages = trimmed.dropLast(1).map { toMessage(it) },
            tools = emptyList(),
            samplerConfig = SamplerConfig(
                topK = override?.topK ?: params.topK,
                topP = override?.topP ?: params.topP,
                temperature = override?.temperature ?: params.temperature,
            ),
            automaticToolCalling = false,
            channels = emptyList(),
            extraContext = emptyMap(),
            loraConfig = null,
            prefillPrefaceOnInit = false,
            maxOutputToken = null,
            thinkingConfig = null,
            enableResponseFormat = false,
        )
        val conversation = current.createConversation(config)
        try {
            emitAll(
                liteRtStream(
                    conversation,
                    contentsOf(trimmed.last().content, images, audio),
                    extraContext(),
                )
            )
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun toMessage(turn: Turn): Message {
        val content = SignatureCodec.guard(turn.content)
        return when (turn.role) {
            "assistant", "model" -> Message.model(content)
            "system" -> Message.system(content)
            else -> Message.user(content)
        }
    }

    private fun isAcceleratorFailure(t: Throwable): Boolean {
        val message = (t.message ?: "").lowercase()
        return message.contains("opencl") ||
            message.contains("accelerator") ||
            message.contains("can not find") ||
            message.contains("failed to create engine")
    }

    private fun switchToCpu(): Boolean {
        val context = appContext ?: return false
        val path = loadedModelPath ?: return false
        val params = loadedParams ?: return false
        if (params.accelerator == Accelerator.CPU) return false
        EnvProbe.logEnvironment(context)
        return try {
            closeInternal()
            loadLiteRt(context, path, params.copy(accelerator = Accelerator.CPU))
            loadedModelPath = path
            loadedParams = params.copy(accelerator = Accelerator.CPU)
            note = "该设备 ${params.accelerator} 推理不可用（缺少 OpenCL 等依赖），已自动改用 CPU"
            true
        } catch (t: Throwable) {
            DiagLog.e("回退 CPU 失败", t)
            false
        }
    }

    private fun liteRtStream(
        conversation: Conversation,
        contents: Contents,
        extra: Map<String, Any>,
    ): Flow<Chunk> = callbackFlow {
        val channelFilter = ThinkingChannelFilter()
        val thinkFilter = ThinkTagFilter()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                val raw = message.toString()
                val (answerPart, channelThinking) = channelFilter.feed(raw)
                val (answer, thinkThinking) = thinkFilter.feed(answerPart)
                val parsedThinking = channelThinking + thinkThinking
                val thinking = message.channels[THOUGHT_CHANNEL] ?: parsedThinking.ifEmpty { null }
                if (first.getAndSet(false)) {
                    DiagLog.d("首个 token: raw='${raw.take(40)}' answer='${answer.take(40)}'")
                }
                if (answer.isEmpty() && thinking == null) return
                val safeAnswer = SignatureCodec.guard(answer)
                val safeThinking = thinking?.let { SignatureCodec.guard(it) }
                if (!trySend(Chunk(safeAnswer, safeThinking)).isSuccess) {
                    DiagLog.w("token 丢弃（缓冲区满），len=${safeAnswer.length}")
                }
            }

            override fun onDone() {
                val (tailChannelAnswer, tailChannelThinking) = channelFilter.flush()
                val (tailAnswer, tailThink) = thinkFilter.feed(tailChannelAnswer)
                val (lastAnswer, lastThink) = thinkFilter.flush()
                val answer = tailAnswer + lastAnswer
                val thinking = tailChannelThinking + tailThink + lastThink
                if (answer.isNotEmpty() || thinking.isNotEmpty()) {
                    trySend(
                        Chunk(
                            SignatureCodec.guard(answer),
                            thinking.ifEmpty { null }?.let { SignatureCodec.guard(it) },
                        )
                    )
                }
                DiagLog.d("生成完成")
                close()
            }

            override fun onError(throwable: Throwable) {
                DiagLog.w("生成出错", throwable)
                if (throwable is CancellationException) close() else close(throwable)
            }
        }
        DiagLog.d("sendMessageAsync 调用，extra=$extra")
        conversation.sendMessageAsync(contents, callback, extra)
        awaitClose { runCatching { conversation.cancelProcess() } }
    }

    private suspend fun loadLlama(modelPath: String, params: Params) {
        val threadCount = java.lang.Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val config = LlamaConfig().apply {
            contextSize = params.maxTokens
            batchSize = 256
            threads = threadCount
            threadsBatch = threadCount
            temperature = params.temperature.toFloat()
            topK = params.topK
            topP = params.topP.toFloat()
            maxTokens = params.maxTokens
            gpuLayers = 0
        }
        val model = LlamaModel.load(modelPath, config)
        llama = model
        llamaHistory.clear()
        val template = runCatching {
            model.javaClass.getMethod("getChatTemplate").invoke(model) as? String
        }.getOrNull()
        llamaFamily = PromptFormat.detect(template ?: modelPath)
        runtime = Runtime.LLAMA_CPP
        activeBackend = "CPU"
        if (params.accelerator != Accelerator.CPU) {
            note = "GGUF 走 llama.cpp，仅支持 CPU；GPU/NPU 请使用 .litertlm 模型"
        }
    }

    private fun llamaChat(prompt: String): Flow<Chunk> = flow {
        val model = llama ?: throw IllegalStateException("模型未加载")
        val params = loadedParams ?: throw IllegalStateException("模型未加载")

        val history = llamaHistory + Turn("user", prompt)
        val formatted = PromptFormat.format(
            llamaFamily,
            params.systemPrompt,
            history,
            params.enableThinking,
        )
        val think = ThinkTagFilter()
        val produced = StringBuilder()
        model.generateStream(formatted, model.config).collect { piece ->
            val (answer, thinking) = think.feed(piece)
            produced.append(answer)
            if (answer.isNotEmpty() || thinking.isNotEmpty()) {
                emit(
                    Chunk(
                        SignatureCodec.guard(answer),
                        thinking.ifEmpty { null }?.let { SignatureCodec.guard(it) },
                    )
                )
            }
        }
        val (tailAnswer, tailThinking) = think.flush()
        produced.append(tailAnswer)
        if (tailAnswer.isNotEmpty() || tailThinking.isNotEmpty()) {
            emit(
                Chunk(
                    SignatureCodec.guard(tailAnswer),
                    tailThinking.ifEmpty { null }?.let { SignatureCodec.guard(it) },
                )
            )
        }
        llamaHistory.clear()
        llamaHistory.addAll(history)
        llamaHistory.add(Turn("assistant", produced.toString()))
    }

    private fun llamaComplete(turns: List<Turn>, override: SamplerOverride?): Flow<Chunk> = flow {
        val model = llama ?: throw IllegalStateException("模型未加载")
        val params = loadedParams ?: throw IllegalStateException("模型未加载")

        var system = params.systemPrompt.takeIf { it.isNotBlank() }
        val body = ArrayList<Turn>()
        for (turn in turns) {
            if (turn.role == "system") system = turn.content else body.add(turn)
        }
        if (body.isEmpty()) throw IllegalArgumentException("messages 为空")
        val (finalSystem, trimmed) = trimToBudget(system, body, params.maxTokens)

        val config = model.config
        config.temperature = (override?.temperature ?: params.temperature).toFloat()
        config.topK = override?.topK ?: params.topK
        config.topP = (override?.topP ?: params.topP).toFloat()

        val formatted = PromptFormat.format(
            llamaFamily,
            finalSystem.orEmpty(),
            trimmed.map { it.copy(content = SignatureCodec.guard(it.content)) },
            params.enableThinking,
        )
        val think = ThinkTagFilter()
        model.generateStream(formatted, config).collect { piece ->
            val (answer, thinking) = think.feed(piece)
            if (answer.isNotEmpty() || thinking.isNotEmpty()) {
                emit(
                    Chunk(
                        SignatureCodec.guard(answer),
                        thinking.ifEmpty { null }?.let { SignatureCodec.guard(it) },
                    )
                )
            }
        }
        val (tailAnswer, tailThinking) = think.flush()
        if (tailAnswer.isNotEmpty() || tailThinking.isNotEmpty()) {
            emit(
                Chunk(
                    SignatureCodec.guard(tailAnswer),
                    tailThinking.ifEmpty { null }?.let { SignatureCodec.guard(it) },
                )
            )
        }
    }

    private fun closeInternal() {
        runCatching { chatConversation?.close() }
        runCatching { engine?.close() }
        runCatching { llama?.close() }
        chatConversation = null
        engine = null
        llama = null
        llamaHistory.clear()
        runtime = Runtime.NONE
        loadedModelPath = null
        loadedParams = null
        activeBackend = null
        note = null
    }
}
